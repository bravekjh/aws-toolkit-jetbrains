// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.credentials

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import software.aws.toolkits.core.ToolkitClientManager
import software.aws.toolkits.core.credentials.CredentialProviderNotFound
import software.aws.toolkits.core.credentials.ToolkitCredentialsChangeListener
import software.aws.toolkits.core.credentials.ToolkitCredentialsProvider
import software.aws.toolkits.core.region.AwsRegion
import software.aws.toolkits.core.utils.tryOrNull
import software.aws.toolkits.jetbrains.core.AwsClientManager
import software.aws.toolkits.jetbrains.core.AwsSdkClient
import software.aws.toolkits.jetbrains.core.credentials.ProjectAccountSettingsManager.AccountSettingsChangedNotifier.AccountSettingsEvent
import software.aws.toolkits.jetbrains.core.credentials.ProjectAccountSettingsManager.Companion.ACCOUNT_SETTINGS_CHANGED
import software.aws.toolkits.jetbrains.core.credentials.profiles.ProfileToolkitCredentialsProviderFactory
import software.aws.toolkits.jetbrains.core.region.AwsRegionProvider
import software.aws.toolkits.jetbrains.utils.MRUList
import software.aws.toolkits.jetbrains.utils.createNotificationExpiringAction
import software.aws.toolkits.jetbrains.utils.createShowMoreInfoDialogAction
import software.aws.toolkits.jetbrains.utils.notifyWarn
import software.aws.toolkits.resources.message

interface ProjectAccountSettingsManager {
    /**
     * Used to be notified about updates to the active account settings by subscribing to [ACCOUNT_SETTINGS_CHANGED]
     */
    interface AccountSettingsChangedNotifier {
        data class AccountSettingsEvent(
            val isLoading: Boolean,
            val credentialsProvider: ToolkitCredentialsProvider?,
            val region: AwsRegion
        )

        fun settingsChanged(event: AccountSettingsEvent)
    }

    /**
     * Setting the active region will add to the recently used list, and evict the least recently used if at max size
     */
    val activeRegion: AwsRegion

    /**
     * Setting the active provider will add to the recently used list, and evict the least recently used if at max size
     */
    val activeCredentialProvider: ToolkitCredentialsProvider
        @Throws(CredentialProviderNotFound::class) get

    fun hasActiveCredentials(): Boolean = try {
        activeCredentialProvider
        true
    } catch (_: CredentialProviderNotFound) {
        false
    }

    /**
     * Returns the list of recently used [AwsRegion]
     */
    fun recentlyUsedRegions(): List<AwsRegion>

    /**
     * Returns the list of recently used [ToolkitCredentialsProvider]
     */
    fun recentlyUsedCredentials(): List<ToolkitCredentialsProvider>

    /**
     * Attempts to change the active credential provider.
     *
     * 1. Broadcasts a [AccountSettingsChangedNotifier] with [AccountSettingsEvent.isLoading] set to true
     * 2. Kicks off an STS callerIdentity call to validate the credentials work in the background
     * 3. If call succeeds, make the requested provider active, broadcast [AccountSettingsEvent.activeCredentialsChanged]
     * 4. If call fails, null out the active provider, broadcast [AccountSettingsEvent.activeCredentialsChanged]
     */
    fun changeCredentialProvider(credentialsProvider: ToolkitCredentialsProvider?)

    /**
     * Changes the active region and broadcasts out a [AccountSettingsEvent.activeRegionChanged]
     */
    fun changeRegion(region: AwsRegion)

    companion object {
        /***
         * [MessageBus] topic for when the active credential profile or region is changed
         */
        val ACCOUNT_SETTINGS_CHANGED: Topic<ProjectAccountSettingsManager.AccountSettingsChangedNotifier> =
            Topic.create(
                "AWS Account setting changed",
                ProjectAccountSettingsManager.AccountSettingsChangedNotifier::class.java
            )

        fun getInstance(project: Project): ProjectAccountSettingsManager =
            ServiceManager.getService(project, ProjectAccountSettingsManager::class.java)
    }
}

fun Project.activeRegion(): AwsRegion = ProjectAccountSettingsManager.getInstance(this).activeRegion
fun Project.activeCredentialProvider(): ToolkitCredentialsProvider = ProjectAccountSettingsManager.getInstance(this).activeCredentialProvider

data class AccountState(
    var activeProfile: String? = null,
    var activeRegion: String = AwsRegionProvider.getInstance().defaultRegion().id,
    var recentlyUsedProfiles: List<String> = mutableListOf(),
    var recentlyUsedRegions: List<String> = mutableListOf()
)

@State(name = "accountSettings", storages = [Storage("aws.xml")])
class DefaultProjectAccountSettingsManager(private val project: Project, private val stsClient: StsClient) :
    ProjectAccountSettingsManager, PersistentStateComponent<AccountState>, Disposable {
    constructor(project: Project, sdkClient: AwsSdkClient) : this(
        project,
        ToolkitClientManager.createNewClient(
            StsClient::class,
            sdkClient.sdkHttpClient,
            Region.US_EAST_1,
            AnonymousCredentialsProvider.create(),
            AwsClientManager.userAgent
        )
    )

    private val credentialManager = CredentialManager.getInstance()
    private val regionProvider = AwsRegionProvider.getInstance()

    // use internal fields so we can bypass the message bus, so we dont accidentally trigger a stack overflow
    @Volatile
    private var activeRegionInternal: AwsRegion = regionProvider.defaultRegion()
    @Volatile
    private var activeProfileInternal: ToolkitCredentialsProvider? = null
    private val recentlyUsedProfiles = MRUList<String>(MAX_HISTORY)
    private val recentlyUsedRegions = MRUList<AwsRegion>(MAX_HISTORY)
    private var isLoading = true

    init {
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(CredentialManager.CREDENTIALS_CHANGED, object : ToolkitCredentialsChangeListener {
                override fun providerRemoved(providerId: String) {
                    if (activeProfileInternal?.id == providerId) {
                        changeCredentialProvider(null)
                    }
                }
            })
    }

    override val activeRegion: AwsRegion
        get() = activeRegionInternal

    override val activeCredentialProvider: ToolkitCredentialsProvider
        @Throws(CredentialProviderNotFound::class)
        get() = activeProfileInternal ?: throw CredentialProviderNotFound(message("credentials.profile.not_configured"))

    override fun recentlyUsedRegions(): List<AwsRegion> = recentlyUsedRegions.elements()

    override fun recentlyUsedCredentials(): List<ToolkitCredentialsProvider> = recentlyUsedProfiles
        .elements()
        .mapNotNull { getCredentialProviderOrNull(it) }

    override fun getState(): AccountState = AccountState(
        activeProfile = if (hasActiveCredentials()) activeCredentialProvider.id else null,
        activeRegion = activeRegionInternal.id,
        recentlyUsedProfiles = recentlyUsedProfiles.elements(),
        recentlyUsedRegions = recentlyUsedRegions.elements().map { it.id }
    )

    override fun loadState(state: AccountState) {
        activeRegionInternal = regionProvider.lookupRegionById(state.activeRegion)

        state.recentlyUsedRegions.reversed()
            .mapNotNull { regionProvider.regions()[it] }
            .forEach { recentlyUsedRegions.add(it) }

        state.recentlyUsedProfiles
            .reversed()
            .forEach { recentlyUsedProfiles.add(it) }

        val activeProfile = state.activeProfile ?: ProfileToolkitCredentialsProviderFactory.DEFAULT_PROFILE_DISPLAY_NAME
        getCredentialProviderOrNull(activeProfile)?.let { provider ->
            changeCredentialProvider(provider)
        }
    }

    override fun changeCredentialProvider(credentialsProvider: ToolkitCredentialsProvider?) {
        activeProfileInternal = null // Null it out while we verify them

        if (credentialsProvider == null) {
            broadcastChangeEvent()
            return
        }

        isLoading = true
        recentlyUsedProfiles.add(credentialsProvider.id)
        broadcastChangeEvent()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                credentialsProvider.isValidOrThrow(stsClient)
                activeProfileInternal = credentialsProvider
            } catch (e: Exception) {
                val title = message("credentials.invalid.title")
                val message = message("credentials.profile.validation_error", credentialsProvider.displayName)
                notifyWarn(
                    title = title,
                    content = message,
                    notificationActions = listOf(
                        createShowMoreInfoDialogAction(
                            message("credentials.invalid.more_info"),
                            title,
                            message,
                            e.localizedMessage
                        ),
                        createNotificationExpiringAction(ActionManager.getInstance().getAction("aws.settings.upsertCredentials"))
                    )
                )
            } finally {
                runInEdt {
                    isLoading = false
                    broadcastChangeEvent()
                }
            }
        }
    }

    override fun changeRegion(region: AwsRegion) {
        activeRegionInternal = region
        recentlyUsedRegions.add(region)
        broadcastChangeEvent()
    }

    override fun dispose() {
        stsClient.close()
    }

    private fun getCredentialProviderOrNull(id: String): ToolkitCredentialsProvider? = tryOrNull {
        credentialManager.getCredentialProvider(id)
    }

    private fun broadcastChangeEvent() {
        val event = AccountSettingsEvent(isLoading, activeProfileInternal, activeRegionInternal)
        project.messageBus.syncPublisher(ACCOUNT_SETTINGS_CHANGED).settingsChanged(event)
    }

    companion object {
        private const val MAX_HISTORY = 5
    }
}