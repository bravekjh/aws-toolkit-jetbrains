// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import software.aws.toolkits.jetbrains.core.explorer.AwsExplorerResourceNode
import software.aws.toolkits.jetbrains.core.explorer.SingleResourceNodeAction
import software.aws.toolkits.jetbrains.utils.notifyError
import software.aws.toolkits.jetbrains.utils.notifyInfo
import software.aws.toolkits.resources.message

abstract class DeleteResourceAction<in T : AwsExplorerResourceNode<*>>(text: String) : SingleResourceNodeAction<T>(text, icon = AllIcons.Actions.Cancel),
    DumbAware {
    final override fun actionPerformed(selected: T, e: AnActionEvent) {
        val resourceName = selected.displayName()
        ApplicationManager.getApplication().invokeLater {
            val response = Messages.showInputDialog(selected.project,
                    message("delete_resource.message", selected.resourceType(), resourceName),
                    message("delete_resource.title", selected.resourceType(), resourceName),
                    Messages.getWarningIcon(),
                    null,
                    object : InputValidator {
                        override fun checkInput(inputString: String?): Boolean = inputString == resourceName

                        override fun canClose(inputString: String?): Boolean = checkInput(inputString)
                    }
            )

            if (response != null) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        performDelete(selected)
                        notifyInfo(message("delete_resource.deleted", selected.resourceType(), resourceName))
                    } catch (e: Exception) {
                        e.notifyError(message("delete_resource.delete_failed", selected.resourceType(), resourceName), selected.project)
                    }
                }
            }
        }
    }

    abstract fun performDelete(selected: T)
}