/*******************************************************************************
 * Copyright (c) 2008 Matthew Hall and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Matthew Hall - initial API and implementation (bug 194734)
 ******************************************************************************/

package org.eclipse.jface.internal.databinding.swt;

import org.eclipse.core.databinding.observable.Diffs;
import org.eclipse.core.databinding.observable.IDecoratingObservable;
import org.eclipse.core.databinding.observable.IObservable;
import org.eclipse.core.databinding.observable.IStaleListener;
import org.eclipse.core.databinding.observable.ObservableTracker;
import org.eclipse.core.databinding.observable.StaleEvent;
import org.eclipse.core.databinding.observable.value.AbstractVetoableValue;
import org.eclipse.core.databinding.observable.value.IObservableValue;
import org.eclipse.core.databinding.observable.value.IValueChangeListener;
import org.eclipse.core.databinding.observable.value.ValueChangeEvent;
import org.eclipse.core.runtime.Assert;
import org.eclipse.jface.databinding.swt.ISWTObservableValue;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Widget;

/**
 * @since 3.3
 * 
 */
public class SWTVetoableValueDecorator extends AbstractVetoableValue implements
		ISWTObservableValue, IDecoratingObservable {

	private IObservableValue decorated;
	private Widget widget;
	private boolean updating;

	private IValueChangeListener valueListener = new IValueChangeListener() {
		public void handleValueChange(ValueChangeEvent event) {
			if (!updating)
				fireValueChange(event.diff);
		}
	};

	private IStaleListener staleListener = new IStaleListener() {
		public void handleStale(StaleEvent staleEvent) {
			fireStale();
		}
	};

	private Listener verifyListener = new Listener() {
		public void handleEvent(Event event) {
			String currentText = (String) decorated.getValue();
			String newText = currentText.substring(0, event.start) + event.text
					+ currentText.substring(event.end);
			if (!fireValueChanging(Diffs.createValueDiff(currentText, newText))) {
				event.doit = false;
			}
		}
	};

	private Listener disposeListener = new Listener() {
		public void handleEvent(Event event) {
			SWTVetoableValueDecorator.this.dispose();
		}
	};

	/**
	 * @param decorated
	 * @param widget
	 */
	public SWTVetoableValueDecorator(IObservableValue decorated, Widget widget) {
		super(decorated.getRealm());
		this.decorated = decorated;
		this.widget = widget;
		Assert
				.isTrue(decorated.getValueType().equals(String.class),
						"SWTVetoableValueDecorator can only decorate observable values of String type"); //$NON-NLS-1$
		widget.addListener(SWT.Dispose, disposeListener);
	}

	private void getterCalled() {
		ObservableTracker.getterCalled(this);
	}

	protected void firstListenerAdded() {
		decorated.addValueChangeListener(valueListener);
		decorated.addStaleListener(staleListener);
		widget.addListener(SWT.Verify, verifyListener);
	}

	protected void lastListenerRemoved() {
		if (decorated != null) {
			decorated.removeValueChangeListener(valueListener);
			decorated.removeStaleListener(staleListener);
		}
		if (widget != null && !widget.isDisposed())
			widget.removeListener(SWT.Verify, verifyListener);
	}

	protected void doSetApprovedValue(Object value) {
		checkRealm();
		updating = true;
		try {
			decorated.setValue(value);
		} finally {
			updating = false;
		}
	}

	protected Object doGetValue() {
		getterCalled();
		return decorated.getValue();
	}

	public Object getValueType() {
		return decorated.getValueType();
	}

	public boolean isStale() {
		getterCalled();
		return decorated.isStale();
	}

	public void dispose() {
		if (decorated != null) {
			decorated.dispose();
			decorated = null;
		}
		if (widget != null && !widget.isDisposed()) {
			widget.removeListener(SWT.Verify, verifyListener);
		}
		this.widget = null;
		super.dispose();
	}

	public Widget getWidget() {
		return widget;
	}

	public IObservable getDecorated() {
		return decorated;
	}
}