package com.mahadi.claudesessions

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.mahadi.claudesessions.ui.TranscriptView
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class ClaudeSessionFileEditor(
    project: Project,
    private val file: ClaudeSessionVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val view = TranscriptView(project, file.session)

    override fun getComponent(): JComponent = view

    override fun getPreferredFocusedComponent(): JComponent = view

    override fun getName(): String = "Claude Session"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}
}
