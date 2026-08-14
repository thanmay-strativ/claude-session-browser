package com.mahadi.claudesessions

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ClaudeSessionFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is ClaudeSessionVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ClaudeSessionFileEditor(project, file as ClaudeSessionVirtualFile)

    override fun getEditorTypeId(): String = "claude-session-transcript"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
