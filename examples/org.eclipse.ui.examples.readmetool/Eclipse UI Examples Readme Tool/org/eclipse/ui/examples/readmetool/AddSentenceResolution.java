package org.eclipse.ui.examples.readmetool;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 * A resolution which inserts a sentence into the readme file 
 */
public class AddSentenceResolution implements IMarkerResolution {
	private IMarker marker;
	/*
	 * @see IMarkerResolution#init(IMarker)
	 */
	public void init(IMarker marker) {
		this.marker = marker;
	}

	/*
	 * @see IMarkerResolution#isAppropriate()
	 */
	public boolean isAppropriate() {
		return true;
	}

	/*
	 * @see IMarkerResolution#getLabel()
	 */
	public String getLabel() {
		return MessageUtil.getString("Add_Sentence"); //$NON-NLS-1$
	}

	/*
	 * @see IMarkerResolution#run()
	 */
	public void run() {
		// Se if there is an open editor on the file containing the marker
		IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (w == null)
			return;
		IWorkbenchPage page = w.getActivePage();
		if (page == null)
			return;
		IEditorPart[] parts = page.getEditors();
		IEditorPart editorPart = null;
		for (int i = 0; i < parts.length; i++) {
			IEditorInput input = parts[i].getEditorInput();
			if (input instanceof IFileEditorInput) {
				if (((IFileEditorInput)input).getFile().equals(marker.getResource())) {
					editorPart = parts[i];
					break;
				}
			}
		}
		if (editorPart == null) {
			// open an editor
			try {
				editorPart = page.openEditor((IFile)marker.getResource());
			} catch (PartInitException e) {
				MessageDialog.openError(
					w.getShell(),
					MessageUtil.getString("Resolution_Error"), //$NON-NLS-1$
					MessageUtil.getString("Unable_to_open_file_editor"));  //$NON-NLS-1$
			}
		}
		if (editorPart == null || !(editorPart instanceof ReadmeEditor))
			return;
		// insert the sentence
		ReadmeEditor editor = (ReadmeEditor)editorPart;
		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		String s = MessageUtil.getString("Simple_sentence"); //$NON-NLS-1$
		try {
			doc.replace(marker.getAttribute(IMarker.CHAR_START, -1), 0, s);
		} catch (BadLocationException e) {
			// ignore
			return;
		}
		// delete the marker
		try {
			marker.delete();
		} catch (CoreException e) {
			e.printStackTrace();
			// ignore
		}
		
	}

}
