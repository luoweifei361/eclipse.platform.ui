/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jface.bindings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.commands.contexts.Context;
import org.eclipse.core.commands.contexts.ContextManager;
import org.eclipse.core.commands.contexts.ContextManagerEvent;
import org.eclipse.core.commands.contexts.IContextManagerListener;
import org.eclipse.jface.contexts.IContextIds;
import org.eclipse.jface.util.Util;
import org.eclipse.swt.SWT;

/**
 * <p>
 * A central repository for bindings -- both in the defined and undefined
 * states. Schemes and bindings can be created and retrieved using this manager.
 * It is possible to listen to changes in the collection of schemes and bindings
 * by attaching a listener to the manager.
 * </p>
 * <p>
 * The binding manager is very sensitive to performance. Misusing the manager
 * can render an application unenjoyable to use. As such, each of the public
 * methods states the current run-time performance. In future releases, it is
 * guaranteed that that method will run in at least the stated time constraint --
 * though it might get faster. Where possible, we have also tried to be memory
 * efficient.
 * </p>
 * <p>
 * <em>EXPERIMENTAL</em>. The commands architecture is currently under
 * development for Eclipse 3.1. This class -- its existence, its name and its
 * methods -- are in flux. Do not use this class yet.
 * </p>
 * 
 * @since 3.1
 */
public final class BindingManager implements IContextManagerListener,
		ISchemeListener {

	/**
	 * This flag can be set to <code>true</code> if the binding manager should
	 * print information to <code>System.out</code> when certain boundary
	 * conditions occur.
	 */
	public static boolean DEBUG = false;

	/**
	 * The separator character used in locales.
	 */
	private static final String LOCALE_SEPARATOR = "_"; //$NON-NLS-1$

	/**
	 * </p>
	 * A utility method for adding entries to a map. The map is checked for
	 * entries at the key. If such an entry exists, it is expected to be a
	 * <code>Collection</code>. The value is then appended to the collection.
	 * If no such entry exists, then a collection is created, and the value
	 * added to the collection.
	 * </p>
	 * 
	 * @param map
	 *            The map to modify; if this value is <code>null</code>, then
	 *            this method simply returns.
	 * @param key
	 *            The key to look up in the map; may be <code>null</code>.
	 * @param value
	 *            The value to look up in the map; may be <code>null</code>.
	 */
	private static final void addReverseLookup(final Map map, final Object key,
			final Object value) {
		if (map == null) {
			return;
		}
		
		final Object currentValue = map.get(key);
		if (currentValue != null) {
			final Collection values = (Collection) currentValue;
			values.add(value);
		} else { // currentValue == null
			final Collection values = new ArrayList(1);
			values.add(value);
			map.put(key, values);
		}
	}

	/**
	 * <p>
	 * Takes a fully-specified string, and converts it into an array of
	 * increasingly less-specific strings. So, for example, "en_GB" would become
	 * ["en_GB", "en", "", null].
	 * </p>
	 * <p>
	 * This method runs in linear time (O(n)) over the length of the string.
	 * </p>
	 * 
	 * @param string
	 *            The string to break apart into its less specific components;
	 *            should not be <code>null</code>.
	 * @param separator
	 *            The separator that indicates a separation between a degrees of
	 *            specificity; should not be <code>null</code>.
	 * @return An array of strings from the most specific (i.e.,
	 *         <code>string</code>) to the least specific (i.e.,
	 *         <code>null</code>).
	 */
	private static final String[] expand(String string, final String separator) {
		// Test for boundary conditions.
		if (string == null || separator == null) {
			return new String[0];
		}

		final List strings = new ArrayList();
		final StringBuffer stringBuffer = new StringBuffer();
		string = string.trim(); // remove whitespace
		if (string.length() > 0) {
			final StringTokenizer stringTokenizer = new StringTokenizer(string,
					separator);
			while (stringTokenizer.hasMoreElements()) {
				if (stringBuffer.length() > 0)
					stringBuffer.append(separator);
				stringBuffer.append(((String) stringTokenizer.nextElement())
						.trim());
				strings.add(stringBuffer.toString());
			}
		}
		Collections.reverse(strings);
		strings.add(Util.ZERO_LENGTH_STRING);
		strings.add(null);
		return (String[]) strings.toArray(new String[strings.size()]);
	}

	/**
	 * The active bindings. This is a map of triggers (
	 * <code>TriggerSequence</code>) to command ids (<code>String</code>).
	 * This value will only be <code>null</code> if the active bindings have
	 * not yet been computed. Otherwise, this value may be empty.
	 */
	private Map activeBindings = null;

	/**
	 * The active bindings indexed by command id. This is a map of command ids (<code>String</code>)
	 * to triggers ( <code>TriggerSequence</code>). This value will only be
	 * <code>null</code> if the active bindings have not yet been computed.
	 * Otherwise, this value may be empty.
	 */
	private Map activeBindingsByCommandId = null;

	/**
	 * The scheme that is currently active. An active scheme is the one that is
	 * currently dictating which bindings will actually work. This value may be
	 * <code>null</code> if there is no active scheme. If the active scheme
	 * becomes undefined, then this should automatically revert to
	 * <code>null</code>.
	 */
	private Scheme activeScheme = null;

	/**
	 * The array of scheme identifiers, starting with the active scheme and
	 * moving up through its parents. This value may be <code>null</code> if
	 * there is no active scheme.
	 */
	private String[] activeSchemeIds = null;

	/**
	 * The number of bindings in the <code>bindings</code> array.
	 */
	private int bindingCount = 0;

	/**
	 * The array of all bindings currently handled by this manager. This array
	 * is the raw list of bindings, as provided to this manager. This value may
	 * be <code>null</code> if there are no bindings. The size of this array
	 * is not necessarily the number of bindings.
	 */
	private Binding[] bindings = null;

	/**
	 * A cache of the bindings previously computed by this manager. This value
	 * may be empty, but it is never <code>null</code>. This is a map of
	 * <code>CachedBindingSet</code> to <code>CachedBindingSet</code>.
	 */
	private Map cachedBindings = new HashMap();

	/**
	 * The context manager for this binding manager. For a binding manager to
	 * function, it needs to listen for changes to the contexts. This value is
	 * guaranteed to never be <code>null</code>.
	 */
	private final ContextManager contextManager;
	
	/**
	 * The number of defined schemes.
	 */
	private int definedSchemeCount = 0;

	/**
	 * The set of all schemes that are defined. This value may be empty, and it
	 * may be <code>null</code>. There are only
	 * <code>definedSchemeCount</code> real values in the array. The rest are
	 * all <code>null</code>.
	 */
	private Scheme[] definedSchemes = null;

	/**
	 * The collection of listener to this binding manager. This collection is
	 * <code>null</code> if there are no listeners.
	 */
	private Collection listeners = null;

	/**
	 * The locale for this manager. This defaults to the current locale. The
	 * value will never be <code>null</code>.
	 */
	private String locale = Locale.getDefault().toString();

	/**
	 * The array of locales, starting with the active locale and moving up
	 * through less specific representations of the locale. For example,
	 * ["en_US", "en", "", null]. This value will never be <code>null</code>.
	 */
	private String[] locales = expand(locale, LOCALE_SEPARATOR);

	/**
	 * The platform for this manager. This defaults to the current platform. The
	 * value will never be <code>null</code>.
	 */
	private String platform = SWT.getPlatform();

	/**
	 * The array of platforms, starting with the active platform and moving up
	 * through less specific representations of the platform. For example,
	 * ["gtk", "", null]. This value will never be <code>null,/code>.
	 */
	private String[] platforms = expand(platform, Util.ZERO_LENGTH_STRING);

	/**
	 * A map of prefixes (<code>TriggerSequence</code>) to a map of
	 * available completions (possibly <code>null</code>, which means there
	 * is an exact match). The available completions is a map of trigger (<code>TriggerSequence</code>)
	 * to command identifier (<code>String</code>). This value may be
	 * <code>null</code> if there is no existing solution.
	 */
	private Map prefixTable = null;

	/**
	 * The map of scheme identifiers (<code>String</code>) to scheme (
	 * <code>Scheme</code>). This value may be empty, but is never
	 * <code>null</code>.
	 */
	private final Map schemesById = new HashMap();

	/**
	 * <p>
	 * Constructs a new instance of <code>BindingManager</code>.
	 * </p>
	 * <p>
	 * This method completes in amortized constant time (O(1)).
	 * </p>
	 * 
	 * @param contextManager
	 *            The context manager that will support this binding manager.
	 *            This value must not be <code>null</code>.
	 */
	public BindingManager(final ContextManager contextManager) {
		if (contextManager == null) {
			throw new NullPointerException(
					"A binding manager requires a context manager"); //$NON-NLS-1$
		}

		this.contextManager = contextManager;
		contextManager.addContextManagerListener(this);
	}

	/**
	 * <p>
	 * Adds a single new binding to the exist array of bindings. If the array is
	 * currently <code>null</code>, then a new array is created and this
	 * binding is added to it. This method does not detect duplicates.
	 * </p>
	 * <p>
	 * This method completes in amortized <code>O(1)</code>.
	 * </p>
	 * 
	 * @param binding
	 *            The binding to be added; must not be <code>null</code>.
	 */
	public final void addBinding(final Binding binding) {
		if (binding == null) {
			throw new NullPointerException("Cannot add a null binding"); //$NON-NLS-1$
		}

		if (bindings == null) {
			bindings = new Binding[1];
		} else if (bindingCount >= bindings.length) {
			final Binding[] oldBindings = bindings;
			bindings = new Binding[oldBindings.length * 2];
			System.arraycopy(oldBindings, 0, bindings, 0, oldBindings.length);
		}
		bindings[bindingCount++] = binding;
		clearCache();
	}

	/**
	 * <p>
	 * Adds a listener to this binding manager. The listener will be notified
	 * when the set of defined schemes or bindings changes. This can be used to
	 * track the global appearance and disappearance of bindings.
	 * </p>
	 * <p>
	 * This method completes in amortized constant time (<code>O(1)</code>).
	 * </p>
	 * 
	 * @param listener
	 *            The listener to attach; must not be <code>null</code>.
	 */
	public final void addBindingManagerListener(
			final IBindingManagerListener listener) {
		if (listener == null) {
			throw new NullPointerException();
		}

		if (listeners == null) {
			listeners = new HashSet();
		}

		listeners.add(listener);
	}

	/**
	 * <p>
	 * Builds a prefix table look-up for a map of active bindings.
	 * </p>
	 * <p>
	 * This method takes <code>O(mn)</code>, where <code>m</code> is the
	 * length of the trigger sequences and <code>n</code> is the number of
	 * bindings.
	 * </p>
	 * 
	 * @param activeBindings
	 *            The map of triggers (<code>TriggerSequence</code>) to
	 *            command ids (<code>String</code>) which are currently
	 *            active. This value may be <code>null</code> if there are no
	 *            active bindings, and it may be empty. It must not be
	 *            <code>null</code>.
	 * @return A map of prefixes (<code>TriggerSequence</code>) to a map of
	 *         available completions (possibly <code>null</code>, which means
	 *         there is an exact match). The available completions is a map of
	 *         trigger (<code>TriggerSequence</code>) to command identifier (<code>String</code>).
	 *         This value will never be <code>null</code>, but may be empty.
	 */
	private final Map buildPrefixTable(final Map activeBindings) {
		final Map prefixTable = new HashMap();

		final Iterator bindingItr = activeBindings.entrySet().iterator();
		while (bindingItr.hasNext()) {
			final Map.Entry entry = (Map.Entry) bindingItr.next();
			final TriggerSequence triggerSequence = (TriggerSequence) entry
					.getKey();

			// Add the perfect match.
			if (!prefixTable.containsKey(triggerSequence)) {
				prefixTable.put(triggerSequence, null);
			}

			final TriggerSequence[] prefixes = triggerSequence.getPrefixes();
			final int prefixesLength = prefixes.length;
			if (prefixesLength == 0) {
				continue;
			}

			// Break apart the trigger sequence.
			final String commandId = (String) entry.getValue();
			for (int i = 0; i < prefixesLength; i++) {
				final TriggerSequence prefix = prefixes[i];
				final Object value = prefixTable.get(prefix);
				if ((prefixTable.containsKey(prefix)) && (value instanceof Map)) {
					((Map) value).put(triggerSequence, commandId);
				} else {
					final Map map = new HashMap();
					prefixTable.put(prefix, map);
					map.put(triggerSequence, commandId);
				}
			}
		}

		return prefixTable;
	}

	/**
	 * <p>
	 * Clears the cache, and the existing solution. If debugging is turned on,
	 * then this will also print a message to standard out.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 */
	private final void clearCache() {
		if (DEBUG) {
			System.out.println("BINDINGS >> Clearing cache"); //$NON-NLS-1$
		}
		cachedBindings.clear();
		clearSolution();
	}

	/**
	 * <p>
	 * Clears the existing solution.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 */
	private final void clearSolution() {
		setActiveBindings(null, null, null);
	}

	/**
	 * <p>
	 * Computes the bindings given the context tree, and inserts them into the
	 * <code>commandIdsByTrigger</code>. It is assumed that
	 * <code>locales</code>,<code>platforsm</code> and
	 * <code>schemeIds</code> correctly reflect the state of the application.
	 * This method does not deal with caching.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @param activeContextTree
	 *            The map representing the tree of active contexts. The map is
	 *            one of child to parent, each being a context id (
	 *            <code>String</code>). The keys are never <code>null</code>,
	 *            but the values may be (i.e., no parent). This map may be
	 *            empty. It may be <code>null</code> if we shouldn't consider
	 *            contexts.
	 * @param commandIdsByTrigger
	 *            The empty of map that is intended to be filled with triggers (
	 *            <code>TriggerSequence</code>) to command identifiers (
	 *            <code>String</code>). This value must not be
	 *            <code>null</code> and must be empty.
	 * @param triggersByCommandId
	 *            The empty of map that is intended to be filled with command
	 *            identifiers (<code>String</code>) to triggers (
	 *            <code>TriggerSequence</code>). This value must either be
	 *            <code>null</code> (indicating that these values are not
	 *            needed), or empty (indicating that this map should be
	 *            computed).
	 */
	private final void computeBindings(final Map activeContextTree,
			final Map commandIdsByTrigger, final Map triggersByCommandId) {
		/*
		 * FIRST PASS: Remove all of the bindings that are marking deletions.
		 */
		final Binding[] trimmedBindings = removeDeletions(bindings);

		/*
		 * SECOND PASS: Just throw in bindings that match the current state. If
		 * there is more than one match for a binding, then create a list.
		 */
		final Map possibleBindings = new HashMap();
		final int length = trimmedBindings.length;
		for (int i = 0; i < length; i++) {
			final Binding binding = trimmedBindings[i];
			boolean found;

			// Check the context.
			final String contextId = binding.getContextId();
			if ((activeContextTree != null)
					&& (!activeContextTree.containsKey(contextId))) {
				continue;
			}

			// Check the locale.
			if (!localeMatches(binding)) {
				continue;
			}

			// Check the platform.
			if (!platformMatches(binding)) {
				continue;
			}

			// Check the scheme ids.
			final String schemeId = binding.getSchemeId();
			found = false;
			for (int j = 0; j < activeSchemeIds.length; j++) {
				if (Util.equals(schemeId, activeSchemeIds[j])) {
					found = true;
					break;
				}
			}
			if (!found) {
				continue;
			}

			// Insert the match into the list of possible matches.
			final TriggerSequence trigger = binding.getTriggerSequence();
			final Object existingMatch = possibleBindings.get(trigger);
			if (existingMatch instanceof Binding) {
				possibleBindings.remove(trigger);
				final Collection matches = new ArrayList();
				matches.add(existingMatch);
				matches.add(binding);
				possibleBindings.put(trigger, matches);

			} else if (existingMatch instanceof Collection) {
				final Collection matches = (Collection) existingMatch;
				matches.add(binding);

			} else {
				possibleBindings.put(trigger, binding);
			}
		}

		/*
		 * THIRD PASS: In this pass, we move any non-conflicting bindings
		 * directly into the map. In the case of conflicts, we apply some
		 * further logic to try to resolve them. If the conflict can't be
		 * resolved, then we log the problem.
		 */
		final Iterator possibleBindingItr = possibleBindings.entrySet()
				.iterator();
		while (possibleBindingItr.hasNext()) {
			final Map.Entry entry = (Map.Entry) possibleBindingItr.next();
			final TriggerSequence trigger = (TriggerSequence) entry.getKey();
			final Object match = entry.getValue();
			/*
			 * What we do depends slightly on whether we are trying to build a
			 * list of all possible bindings (disregarding context), or a flat
			 * map given the currently active contexts.
			 */
			if (activeContextTree == null) {
				// We are building the list of all possible bindings.
				final Collection bindings = new ArrayList();
				if (match instanceof Binding) {
					bindings.add(match);
					commandIdsByTrigger.put(trigger, bindings);
					addReverseLookup(triggersByCommandId, ((Binding) match)
							.getCommandId(), trigger);

				} else if (match instanceof Collection) {
					bindings.addAll(resolveConflicts((Collection) match));
					commandIdsByTrigger.put(trigger, bindings);
					
					final Iterator matchItr = bindings.iterator();
					while (matchItr.hasNext()) {
						addReverseLookup(triggersByCommandId,
								((Binding) matchItr.next()).getCommandId(),
								trigger);
					}
				}

			} else {
				// We are building the flat map of trigger to commands.
				if (match instanceof Binding) {
					final String commandId = ((Binding) match).getCommandId();
					commandIdsByTrigger.put(trigger, commandId);
					addReverseLookup(triggersByCommandId, commandId, trigger);

				} else if (match instanceof Collection) {
					final Binding winner = resolveConflicts((Collection) match,
							activeContextTree);
					if (winner == null) {
						if (DEBUG) {
							System.out
									.println("BINDINGS >> A conflict occurred for " + trigger); //$NON-NLS-1$
							System.out.println("BINDINGS >>     " + match); //$NON-NLS-1$
						}
					} else {
						final String commandId = winner.getCommandId();
						commandIdsByTrigger.put(trigger, commandId);
						addReverseLookup(triggersByCommandId, commandId,
								trigger);
					}
				}
			}
		}
	}

	/**
	 * <p>
	 * Notifies this manager that the context manager has changed. This method
	 * is intended for internal use only.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 */
	public final void contextManagerChanged(
			final ContextManagerEvent contextManagerEvent) {
		if (contextManagerEvent.haveActiveContextsChanged()) {
			clearSolution();
		}
	}

	/**
	 * <p>
	 * Creates a tree of context identifiers, representing the hierarchical
	 * structure of the given contexts. The tree is structured as a mapping from
	 * child to parent.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the height of the context tree.
	 * </p>
	 * 
	 * @param contextIds
	 *            The set of context identifiers to be converted into a tree;
	 *            must not be <code>null</code>.
	 * @return The tree of contexts to use; may be empty, but never
	 *         <code>null</code>. The keys and values are both strings.
	 */
	private final Map createContextTreeFor(final Set contextIds) {
		final Map contextTree = new HashMap();

		final Iterator contextIdItr = contextIds.iterator();
		while (contextIdItr.hasNext()) {
			String childContextId = (String) contextIdItr.next();
			while (childContextId != null) {
				final Context childContext = contextManager
						.getContext(childContextId);

				try {
					final String parentContextId = childContext.getParentId();
					contextTree.put(childContextId, parentContextId);
					childContextId = parentContextId;
				} catch (final NotDefinedException e) {
					break; // stop ascending
				}
			}
		}

		return contextTree;
	}

	/**
	 * <p>
	 * Creates a tree of context identifiers, representing the hierarchical
	 * structure of the given contexts. The tree is structured as a mapping from
	 * child to parent. In this tree, the key binding specific filtering of
	 * contexts will have taken place.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n^2)</code>, where <code>n</code>
	 * is the height of the context tree.
	 * </p>
	 * 
	 * @param contextIds
	 *            The set of context identifiers to be converted into a tree;
	 *            must not be <code>null</code>.
	 * @return The tree of contexts to use; may be empty, but never
	 *         <code>null</code>. The keys and values are both strings.
	 */
	private final Map createFilteredContextTreeFor(final Set contextIds) {
		// Check to see whether a dialog or window is active.
		boolean dialog = false;
		boolean window = false;
		Iterator contextIdItr = contextIds.iterator();
		while (contextIdItr.hasNext()) {
			final String contextId = (String) contextIdItr.next();
			if (IContextIds.CONTEXT_ID_DIALOG.equals(contextId)) {
				dialog = true;
				continue;
			}
			if (IContextIds.CONTEXT_ID_WINDOW.equals(contextId)) {
				window = true;
				continue;
			}
		}

		/*
		 * Remove all context identifiers for contexts whose parents are dialog
		 * or window, and the corresponding dialog or window context is not
		 * active.
		 */
		try {
			contextIdItr = contextIds.iterator();
			while (contextIdItr.hasNext()) {
				String contextId = (String) contextIdItr.next();
				Context context = contextManager.getContext(contextId);
				String parentId = context.getParentId();
				while (parentId != null) {
					if (IContextIds.CONTEXT_ID_DIALOG.equals(parentId)) {
						if (!dialog) {
							contextIdItr.remove();
						}
						break;
					}
					if (IContextIds.CONTEXT_ID_WINDOW.equals(parentId)) {
						if (!window) {
							contextIdItr.remove();
						}
						break;
					}
					if (IContextIds.CONTEXT_ID_DIALOG_AND_WINDOW
							.equals(parentId)) {
						if ((!window) && (!dialog)) {
							contextIdItr.remove();
						}
						break;
					}

					context = contextManager.getContext(parentId);
					parentId = context.getParentId();
				}
			}
		} catch (NotDefinedException e) {
			if (DEBUG) {
				System.out.println("BINDINGS >>> NotDefinedException('" //$NON-NLS-1$
						+ e.getMessage()
						+ "') while filtering dialog/window contexts"); //$NON-NLS-1$
			}
		}

		return createContextTreeFor(contextIds);
	}

	/**
	 * <p>
	 * Notifies all of the listeners to this manager that the defined or active
	 * schemes of bindings have changed.
	 * </p>
	 * <p>
	 * The time this method takes to complete is dependent on external
	 * listeners.
	 * </p>
	 * 
	 * @param event
	 *            The event to send to all of the listeners; must not be
	 *            <code>null</code>.
	 */
	private final void fireBindingManagerChanged(final BindingManagerEvent event) {
		if (event == null)
			throw new NullPointerException();

		if (listeners != null) {
			final Iterator listenerItr = listeners.iterator();
			while (listenerItr.hasNext()) {
				final IBindingManagerListener listener = (IBindingManagerListener) listenerItr
						.next();
				listener.bindingManagerChanged(event);
			}
		}
	}

	/**
	 * <p>
	 * Returns the active bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the active bindings are
	 * not yet computed, then this completes in <code>O(nn)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @return The map of triggers (<code>TriggerSequence</code>) to command
	 *         ids (<code>String</code>) which are currently active. This
	 *         value may be <code>null</code> if there are no active bindings,
	 *         and it may be empty.
	 */
	private final Map getActiveBindings() {
		if (activeBindings == null) {
			recomputeBindings();
		}

		return Collections.unmodifiableMap(activeBindings);
	}

	/**
	 * <p>
	 * Returns the active bindings indexed by command identifier.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the active bindings are
	 * not yet computed, then this completes in <code>O(nn)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @return The map of command ids (<code>String</code>) to triggers (<code>TriggerSequence</code>)
	 *         which are currently active. This value may be <code>null</code>
	 *         if there are no active bindings, and it may be empty.
	 */
	private final Map getActiveBindingsByCommandId() {
		if (activeBindingsByCommandId == null) {
			recomputeBindings();
		}

		return Collections.unmodifiableMap(activeBindingsByCommandId);
	}

	/**
	 * <p>
	 * Computes the bindings for the current state of the application, but
	 * disregarding the current contexts. This can be useful when trying to
	 * display all the possible bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @return A map of trigger (<code>TriggerSequence</code>) to bindings (
	 *         <code>Collection</code> containing <code>Binding</code>).
	 *         This map may be empty, but it is never <code>null</code>.
	 */
	public final Map getActiveBindingsDisregardingContext() {
		if (bindings == null) {
			// Not yet initialized. This is happening too early. Do nothing.
			return Collections.EMPTY_MAP;
		}

		// Build a cached binding set for that state.
		final CachedBindingSet bindingCache = new CachedBindingSet(null,
				locales, platforms, activeSchemeIds);

		/*
		 * Check if the cached binding set already exists. If so, simply set the
		 * active bindings and return.
		 */
		CachedBindingSet existingCache = (CachedBindingSet) cachedBindings
				.get(bindingCache);
		if (existingCache == null) {
			existingCache = bindingCache;
			cachedBindings.put(existingCache, existingCache);
		}
		Map commandIdsByTrigger = existingCache.getCommandIdsByTrigger();
		if (commandIdsByTrigger != null) {
			if (DEBUG) {
				System.out.println("BINDINGS >> Cache hit"); //$NON-NLS-1$
			}

			return Collections.unmodifiableMap(commandIdsByTrigger);
		}

		// There is no cached entry for this.
		if (DEBUG) {
			System.out.println("BINDINGS >> Cache miss"); //$NON-NLS-1$
		}

		// Compute the active bindings.
		commandIdsByTrigger = new HashMap();
		computeBindings(null, commandIdsByTrigger, null);
		existingCache.setCommandIdsByTrigger(commandIdsByTrigger);
		return Collections.unmodifiableMap(commandIdsByTrigger);
	}

	/**
	 * <p>
	 * Computes the bindings for the current state of the application, but
	 * disregarding the current contexts. This can be useful when trying to
	 * display all the possible bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @return All of the active bindings (<code>Binding</code>), not sorted
	 *         in any fashion. This collection may be empty, but it is never
	 *         <code>null</code>.
	 */
	public final Collection getActiveBindingsDisregardingContextFlat() {
		final Collection bindingCollections = getActiveBindingsDisregardingContext()
				.values();
		final Collection mergedBindings = new ArrayList();
		final Iterator bindingCollectionItr = bindingCollections.iterator();
		while (bindingCollectionItr.hasNext()) {
			final Collection bindingCollection = (Collection) bindingCollectionItr
					.next();
			if ((bindingCollection != null) && (!bindingCollection.isEmpty())) {
				mergedBindings.addAll(bindingCollection);
			}
		}

		return mergedBindings;
	}

	/**
	 * <p>
	 * Returns the active bindings for a particular command identifier. This
	 * method operates in O(n) time over the number of bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the active bindings are
	 * not yet computed, then this completes in <code>O(nn)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @param commandId
	 *            The identifier for the command whose bindings you wish to
	 *            find. This argument may be <code>null</code>.
	 * @return The array of active triggers (<code>TriggerSequence</code>)
	 *         for a particular command identifier. This value is guaranteed to
	 *         never be <code>null</code>, but it may be empty.
	 */
	public final TriggerSequence[] getActiveBindingsFor(final String commandId) {
		final Object object = getActiveBindingsByCommandId().get(commandId);
		if (object instanceof Collection) {
			final Collection collection = (Collection) object;
			return (TriggerSequence[]) collection
					.toArray(new TriggerSequence[collection.size()]);
		}

		return new TriggerSequence[0];
	}

	/**
	 * <p>
	 * Gets the currently active scheme.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @return The active scheme; may be <code>null</code> if there is no
	 *         active scheme. This scheme is guaranteed to be defined.
	 */
	public final Scheme getActiveScheme() {
		return activeScheme;
	}

	/**
	 * <p>
	 * Returns the set of all bindings managed by this class. This set is
	 * wrapped in a <code>Collections.unmodifiableSet</code>. This is to
	 * prevent modification of the manager's internal data structures.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @return The array of all bindings. This value may be <code>null</code>
	 *         and it may be empty.
	 */
	public final Binding[] getBindings() {
		if (bindings == null) {
			return null;
		}

		final Binding[] returnValue = new Binding[bindingCount];
		System.arraycopy(bindings, 0, returnValue, 0, bindingCount);
		return returnValue;
	}

	/**
	 * <p>
	 * Returns the array of schemes that are defined.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @return The array of defined schemes; this value may be empty, but it is
	 *         never <code>null</code>.
	 */
	public final Scheme[] getDefinedSchemes() {
		if ((definedSchemes == null) || (definedSchemeCount == 0)) {
			return new Scheme[0];
		}
		
		final Scheme[] returnValue = new Scheme[definedSchemeCount];
		System.arraycopy(definedSchemes, 0, returnValue, 0, definedSchemeCount);
		return returnValue;
	}

	/**
	 * <p>
	 * Returns the active locale for this binding manager. The locale is in the
	 * same format as <code>Locale.getDefault().toString()</code>.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @return The active locale; never <code>null</code>.
	 */
	public final String getLocale() {
		return locale;
	}

	/**
	 * <p>
	 * Returns all of the possible bindings that start with the given trigger
	 * (but are not equal to the given trigger).
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the bindings aren't
	 * currently computed, then this completes in <code>O(n)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @param trigger
	 *            The prefix to look for; must not be <code>null</code>.
	 * @return A map of triggers (<code>TriggerSequence</code>) to command
	 *         identifier (<code>String</code>). This map may be empty, but
	 *         it is never <code>null</code>.
	 */
	public final Map getPartialMatches(final TriggerSequence trigger) {
		final Map partialMatches = (Map) getPrefixTable().get(trigger);
		if (partialMatches == null) {
			return Collections.EMPTY_MAP;
		}

		return partialMatches;
	}

	/**
	 * <p>
	 * Returns the command identifier for the active binding matching this
	 * trigger, if any.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the bindings aren't
	 * currently computed, then this completes in <code>O(n)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @param trigger
	 *            The trigger to match; may be <code>null</code>.
	 * @return The command identifier that matches, if any; <code>null</code>
	 *         otherwise.
	 */
	public final String getPerfectMatch(final TriggerSequence trigger) {
		return (String) getActiveBindings().get(trigger);
	}

	/**
	 * <p>
	 * Returns the active platform for this binding manager. The platform is in
	 * the same format as <code>SWT.getPlatform()</code>.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @return The active platform; never <code>null</code>.
	 */
	public final String getPlatform() {
		return platform;
	}

	/**
	 * <p>
	 * Returns the prefix table.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the active bindings are
	 * not yet computed, then this completes in <code>O(n)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @return A map of prefixes (<code>TriggerSequence</code>) to a map of
	 *         available completions (possibly <code>null</code>, which means
	 *         there is an exact match). The available completions is a map of
	 *         trigger (<code>TriggerSequence</code>) to command identifier (<code>String</code>).
	 *         This value will never be <code>null</code> but may be empty.
	 */
	private final Map getPrefixTable() {
		if (prefixTable == null) {
			recomputeBindings();
		}

		return Collections.unmodifiableMap(prefixTable);
	}

	/**
	 * <p>
	 * Gets the scheme with the given identifier. If the scheme does not already
	 * exist, then a new (undefined) scheme is created with that identifier.
	 * This guarantees that schemes will remain unique.
	 * </p>
	 * <p>
	 * This method completes in amortized <code>O(1)</code>.
	 * </p>
	 * 
	 * @param identifier
	 *            The identifier for the scheme to retrieve; must not be
	 *            <code>null</code>.
	 * @return A scheme with the given identifier.
	 */
	public final Scheme getScheme(final String identifier) {
		if (identifier == null) {
			throw new NullPointerException(
					"Cannot get a scheme with a null identifier"); //$NON-NLS-1$
		}

		Scheme scheme = (Scheme) schemesById.get(identifier);
		if (scheme == null) {
			scheme = new Scheme(identifier);
			schemesById.put(identifier, scheme);
			scheme.addSchemeListener(this);
		}

		return scheme;
	}

	/**
	 * <p>
	 * Ascends all of the parents of the scheme until no more parents are found.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the height of the context tree.
	 * </p>
	 * 
	 * @param schemeId
	 *            The id of the scheme for which the parents should be found;
	 *            may be <code>null</code>.
	 * @return The array of scheme ids (<code>String</code>) starting with
	 *         <code>schemeId</code> and then ascending through its ancestors.
	 */
	private final String[] getSchemeIds(String schemeId) {
		final List strings = new ArrayList();
		while (schemeId != null) {
			strings.add(schemeId);
			try {
				schemeId = getScheme(schemeId).getParentId();
			} catch (final NotDefinedException e) {
				return new String[0];
			}
		}

		return (String[]) strings.toArray(new String[strings.size()]);
	}

	/**
	 * <p>
	 * Returns whether the given trigger sequence is a partial match for the
	 * given sequence.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the bindings aren't
	 * currently computed, then this completes in <code>O(n)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @param trigger
	 *            The sequence which should be the prefix for some binding;
	 *            should not be <code>null</code>.
	 * @return <code>true</code> if the trigger can be found in the active
	 *         bindings; <code>false</code> otherwise.
	 */
	public final boolean isPartialMatch(final TriggerSequence trigger) {
		return (getPrefixTable().get(trigger) != null);
	}

	/**
	 * <p>
	 * Returns whether the given trigger sequence is a perfect match for the
	 * given sequence.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>. If the bindings aren't
	 * currently computed, then this completes in <code>O(n)</code>, where
	 * <code>n</code> is the number of bindings.
	 * </p>
	 * 
	 * @param trigger
	 *            The sequence which should match exactly; should not be
	 *            <code>null</code>.
	 * @return <code>true</code> if the trigger can be found in the active
	 *         bindings; <code>false</code> otherwise.
	 */
	public final boolean isPerfectMatch(final TriggerSequence trigger) {
		return getActiveBindings().containsKey(trigger);
	}

	/**
	 * <p>
	 * Tests whether the locale for the binding matches one of the active
	 * locales.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of active locales.
	 * </p>
	 * 
	 * @param binding
	 *            The binding with which to test; must not be <code>null</code>.
	 * @return <code>true</code> if the binding's locale matches;
	 *         <code>false</code> otherwise.
	 */
	private final boolean localeMatches(final Binding binding) {
		boolean matches = false;

		final String locale = binding.getLocale();
		if (locale == null) {
			return true; // shortcut a common case
		}

		for (int i = 0; i < locales.length; i++) {
			if (Util.equals(locales[i], locale)) {
				matches = true;
				break;
			}
		}

		return matches;
	}

	/**
	 * <p>
	 * Tests whether the platform for the binding matches one of the active
	 * platforms.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of active platforms.
	 * </p>
	 * 
	 * @param binding
	 *            The binding with which to test; must not be <code>null</code>.
	 * @return <code>true</code> if the binding's platform matches;
	 *         <code>false</code> otherwise.
	 */
	private final boolean platformMatches(final Binding binding) {
		boolean matches = false;

		final String platform = binding.getPlatform();
		if (platform == null) {
			return true; // shortcut a common case
		}

		for (int i = 0; i < platforms.length; i++) {
			if (Util.equals(platforms[i], platform)) {
				matches = true;
				break;
			}
		}

		return matches;
	}

	/**
	 * <p>
	 * This recomputes the bindings based on changes to the state of the world.
	 * This computation can be triggered by changes to contexts, the active
	 * scheme, the locale, or the platform. This method tries to use the cache
	 * of pre-computed bindings, if possible. When this method completes,
	 * <code>activeBindings</code> will be set to the current set of bindings
	 * and <code>cachedBindings</code> will contain an instance of
	 * <code>CachedBindingSet</code> representing these bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n+pn)</code>, where <code>n</code>
	 * is the number of bindings, and <code>p</code> is the average number of
	 * triggers in a trigger sequence.
	 * </p>
	 */
	private final void recomputeBindings() {
		if (bindings == null) {
			// Not yet initialized. This is happening too early. Do nothing.
			setActiveBindings(Collections.EMPTY_MAP, Collections.EMPTY_MAP,
					Collections.EMPTY_MAP);
			return;
		}

		// Figure out the current state.
		final Set activeContextIds = new HashSet(contextManager
				.getActiveContextIds());
		final Map activeContextTree = createFilteredContextTreeFor(activeContextIds);

		// Build a cached binding set for that state.
		final CachedBindingSet bindingCache = new CachedBindingSet(
				activeContextTree, locales, platforms, activeSchemeIds);

		/*
		 * Check if the cached binding set already exists. If so, simply set the
		 * active bindings and return.
		 */
		CachedBindingSet existingCache = (CachedBindingSet) cachedBindings
				.get(bindingCache);
		if (existingCache == null) {
			existingCache = bindingCache;
			cachedBindings.put(existingCache, existingCache);
		}
		Map commandIdsByTrigger = existingCache.getCommandIdsByTrigger();
		if (commandIdsByTrigger != null) {
			if (DEBUG) {
				System.out.println("BINDINGS >> Cache hit"); //$NON-NLS-1$
			}
			setActiveBindings(commandIdsByTrigger, existingCache
					.getTriggersByCommandId(), existingCache.getPrefixTable());
			return;
		}

		// There is no cached entry for this.
		if (DEBUG) {
			System.out.println("BINDINGS >> Cache miss"); //$NON-NLS-1$
		}

		// Compute the active bindings.
		commandIdsByTrigger = new HashMap();
		final Map triggersByCommandId = new HashMap();
		computeBindings(activeContextTree, commandIdsByTrigger,
				triggersByCommandId);
		existingCache.setCommandIdsByTrigger(commandIdsByTrigger);
		existingCache.setTriggersByCommandId(triggersByCommandId);
		setActiveBindings(commandIdsByTrigger, triggersByCommandId,
				buildPrefixTable(commandIdsByTrigger));
		existingCache.setPrefixTable(prefixTable);
	}

	/**
	 * <p>
	 * Removes a listener from this binding manager.
	 * </p>
	 * <p>
	 * This method completes in amortized <code>O(1)</code>.
	 * </p>
	 * 
	 * @param listener
	 *            The listener to be removed; must not be <code>null</code>.
	 */
	public final void removeBindingManagerListener(
			final IBindingManagerListener listener) {
		if (listener == null) {
			throw new NullPointerException();
		}

		if (listeners == null) {
			return;
		}

		listeners.remove(listener);

		if (listeners.isEmpty()) {
			listeners = null;
		}
	}

	/**
	 * <p>
	 * Removes any binding that matches the given values -- regardless of
	 * command identifier.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @param sequence
	 *            The sequence to look for; may be <code>null</code>.
	 * @param schemeId
	 *            The scheme id to look for; may be <code>null</code>.
	 * @param contextId
	 *            The context id to look for; may be <code>null</code>.
	 * @param locale
	 *            The locale to look for; may be <code>null</code>.
	 * @param platform
	 *            The platform to look for; may be <code>null</code>.
	 * @param windowManager
	 *            The window manager to look for; may be <code>null</code>.
	 *            TODO Currently ignored.
	 * @param type
	 *            The type to look for.
	 * 
	 */
	public final void removeBindings(final TriggerSequence sequence,
			final String schemeId, final String contextId, final String locale,
			final String platform, final String windowManager, final int type) {
		if ((bindings == null) || (bindingCount < 1)) {
			return;
		}

		final Binding[] newBindings = new Binding[bindings.length];
		boolean bindingsChanged = false;
		int index = 0;
		for (int i = 0; i < bindingCount; i++) {
			final Binding binding = bindings[i];
			boolean equals = true;
			equals &= Util.equals(sequence, binding.getTriggerSequence());
			equals &= Util.equals(schemeId, binding.getSchemeId());
			equals &= Util.equals(contextId, binding.getContextId());
			equals &= Util.equals(locale, binding.getLocale());
			equals &= Util.equals(platform, binding.getPlatform());
			equals &= (type == binding.getType());
			if (equals) {
				bindingsChanged = true;
			} else {
				newBindings[index++] = binding;
			}
		}

		if (bindingsChanged) {
			this.bindings = newBindings;
			bindingCount = index;
			clearCache();
		}
	}

	/**
	 * <p>
	 * Attempts to remove deletion markers from the collection of bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code>
	 * is the number of bindings.
	 * </p>
	 * 
	 * @param bindings
	 *            The bindings from which the deleted items should be removed.
	 *            This array should not be <code>null</code>, but may be
	 *            empty.
	 * @return The array of bindings with the deletions removed; never
	 *         <code>null</code>, but may be empty. Contains only instances
	 *         of <code>Binding</code>.
	 */
	private final Binding[] removeDeletions(final Binding[] bindings) {
		final Map deletions = new HashMap();
		final Binding[] bindingsCopy = new Binding[bindingCount];
		System.arraycopy(bindings, 0, bindingsCopy, 0, bindingCount);
		int deletedCount = 0;

		// Extract the deletions.
		for (int i = 0; i < bindingCount; i++) {
			final Binding binding = bindingsCopy[i];
			if ((binding.getCommandId() == null) && (localeMatches(binding))
					&& (platformMatches(binding))) {
				deletions.put(binding.getTriggerSequence(), binding);
				bindingsCopy[i] = null;
				deletedCount++;
			}
		}

		if (DEBUG) {
			System.out
					.println("BINDINGS >> There are " + deletions.size() + " deletion markers"); //$NON-NLS-1$//$NON-NLS-2$
		}

		// Remove the deleted items.
		for (int i = 0; i < bindingCount; i++) {
			final Binding binding = bindingsCopy[i];
			if (binding != null) {
				final Object deletion = deletions.get(binding
						.getTriggerSequence());
				if ((deletion != null)
						&& (((Binding) deletion).deletes(binding))) {
					bindingsCopy[i] = null;
					deletedCount++;
				}
			}
		}

		// Compact the array.
		final Binding[] returnValue = new Binding[bindingCount - deletedCount];
		int index = 0;
		for (int i = 0; i < bindingCount; i++) {
			final Binding binding = bindingsCopy[i];
			if (binding != null) {
				returnValue[index++] = binding;
			}
		}

		return returnValue;
	}

	/**
	 * <p>
	 * Attempts to resolve the conflicts for the given bindings -- irrespective
	 * of the currently active contexts. This means that type and scheme will be
	 * considered.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @param bindings
	 *            The bindings which all match the same trigger sequence; must
	 *            not be <code>null</code>, and should contain at least two
	 *            items. This collection should only contain instances of
	 *            <code>Binding</code> (i.e., no <code>null</code> values).
	 * @return The collection of bindings which match the current scheme.
	 */
	private final Collection resolveConflicts(final Collection bindings) {
		final Collection matches = new ArrayList();
		final Iterator bindingItr = bindings.iterator();
		Binding bestMatch = (Binding) bindingItr.next();

		/*
		 * Iterate over each binding and compares it with the best match. If a
		 * better match is found, then replace the best match and clear the
		 * collection. If the current binding is equivalent, then simply add it
		 * to the collection of matches. If the current binding is worse, then
		 * do nothing.
		 */
		while (bindingItr.hasNext()) {
			final Binding current = (Binding) bindingItr.next();

			/*
			 * SCHEME: Test whether the current is in a child scheme. Bindings
			 * defined in a child scheme will take priority over bindings
			 * defined in a parent scheme -- assuming that consulting their
			 * contexts led to a conflict.
			 */
			final String currentScheme = current.getSchemeId();
			final String bestScheme = bestMatch.getSchemeId();
			if (!currentScheme.equals(bestScheme)) {
				boolean goToNextBinding = false;
				for (int i = 0; i < activeSchemeIds.length; i++) {
					final String schemePointer = activeSchemeIds[i];
					if (currentScheme.equals(schemePointer)) {
						// the current wins
						bestMatch = current;
						matches.clear();
						matches.add(current);
						goToNextBinding = true;
						break;

					} else if (bestScheme.equals(schemePointer)) {
						// the best wins
						goToNextBinding = true;
						break;

					}
				}
				if (goToNextBinding) {
					continue;
				}
			}

			/*
			 * TYPE: Test for type superiority.
			 */
			if (current.getType() > bestMatch.getType()) {
				bestMatch = current;
				matches.clear();
				matches.add(current);
				continue;
			} else if (bestMatch.getType() > current.getType()) {
				continue;
			}

			// The bindings are equivalent.
			matches.add(current);
		}

		// Return all of the matches.
		return matches;
	}

	/**
	 * <p>
	 * Attempts to resolve the conflicts for the given bindings.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @param bindings
	 *            The bindings which all match the same trigger sequence; must
	 *            not be <code>null</code>, and should contain at least two
	 *            items. This collection should only contain instances of
	 *            <code>Binding</code> (i.e., no <code>null</code> values).
	 * @param activeContextTree
	 *            The tree of contexts to be used for all of the comparison. All
	 *            of the keys should be active context identifiers (i.e., never
	 *            <code>null</code>). The values will be their parents (i.e.,
	 *            possibly <code>null</code>). Both keys and values are
	 *            context identifiers (<code>String</code>). This map should
	 *            never be empty, and must never be <code>null</code>.
	 * @return The binding which best matches the current state. If there is a
	 *         tie, then return <code>null</code>.
	 */
	private final Binding resolveConflicts(final Collection bindings,
			final Map activeContextTree) {
		/*
		 * This flag is used to indicate when the bestMatch binding conflicts
		 * with another binding. We keep the best match binding so that we know
		 * if we find a better binding. However, if we don't find a better
		 * binding, then we known to return null.
		 */
		boolean conflict = false;

		final Iterator bindingItr = bindings.iterator();
		Binding bestMatch = (Binding) bindingItr.next();

		/*
		 * Iterate over each binding and compare it with the best match. If a
		 * better match is found, then replace the best match and set the
		 * conflict flag to false. If a conflict is found, then leave the best
		 * match and set the conflict flag. Otherwise, just continue.
		 */
		while (bindingItr.hasNext()) {
			final Binding current = (Binding) bindingItr.next();

			/*
			 * CONTEXTS: Check for context superiority, only if we care about
			 * contexts.
			 */
			final String currentContext = current.getContextId();
			final String bestContext = bestMatch.getContextId();
			if (!currentContext.equals(bestContext)) {
				boolean goToNextBinding = false;

				// Ascend the current's context tree.
				String contextPointer = currentContext;
				while (contextPointer != null) {
					if (contextPointer.equals(bestContext)) {
						// the current wins
						bestMatch = current;
						conflict = false;
						goToNextBinding = true;
						break;
					}
					contextPointer = (String) activeContextTree
							.get(contextPointer);
				}

				// Ascend the best match's context tree.
				contextPointer = bestContext;
				while (contextPointer != null) {
					if (contextPointer.equals(currentContext)) {
						// the best wins
						goToNextBinding = true;
						break;
					}
					contextPointer = (String) activeContextTree
							.get(contextPointer);
				}

				if (goToNextBinding) {
					continue;
				}
			}

			/*
			 * SCHEME: Test whether the current is in a child scheme. Bindings
			 * defined in a child scheme will take priority over bindings
			 * defined in a parent scheme -- assuming that consulting their
			 * contexts led to a conflict.
			 */
			final String currentScheme = current.getSchemeId();
			final String bestScheme = bestMatch.getSchemeId();
			if (!currentScheme.equals(bestScheme)) {
				boolean goToNextBinding = false;
				for (int i = 0; i < activeSchemeIds.length; i++) {
					final String schemePointer = activeSchemeIds[i];
					if (currentScheme.equals(schemePointer)) {
						// the current wins
						bestMatch = current;
						conflict = false;
						goToNextBinding = true;
						break;

					} else if (bestScheme.equals(schemePointer)) {
						// the best wins
						goToNextBinding = true;
						break;

					}

				}
				if (goToNextBinding) {
					continue;
				}
			}

			/*
			 * TYPE: Test for type superiority.
			 */
			if (current.getType() > bestMatch.getType()) {
				bestMatch = current;
				conflict = false;
				continue;
			} else if (bestMatch.getType() > current.getType()) {
				continue;
			}

			// We could not resolve the conflict between these two.
			conflict = true;
		}

		// If the best match represents a conflict, then return null.
		if (conflict) {
			return null;
		}

		// Otherwise, we have a winner....
		return bestMatch;
	}

	/**
	 * <p>
	 * Notifies this manager that a scheme has changed. This method is intended
	 * for internal use only.
	 * </p>
	 * <p>
	 * This method calls out to listeners, and so the time it takes to complete
	 * is dependent on third-party code.
	 * </p>
	 */
	public final void schemeChanged(final SchemeEvent schemeEvent) {
		if (schemeEvent.hasDefinedChanged()) {
			final Scheme scheme = schemeEvent.getScheme();

			final boolean schemeIdAdded = scheme.isDefined();
			boolean activeSchemeChanged = false;
			if (schemeIdAdded) {
				/*
				 * Add the defined scheme to the array -- creating and growing
				 * the array as necessary. 
				 */
				if (definedSchemes == null) {
					definedSchemes = new Scheme[] { scheme };
					definedSchemeCount = 1;

				} else if (definedSchemeCount < definedSchemes.length) {
					definedSchemes[definedSchemeCount++] = scheme;

				} else {
					// Double the array size.
					final Scheme[] newArray = new Scheme[definedSchemes.length * 2];
					System.arraycopy(definedSchemes, 0, newArray, 0,
							definedSchemes.length);
					definedSchemes = newArray;
					definedSchemes[definedSchemeCount++] = scheme;
				}
			} else {
				/*
				 * Remove the scheme from the array of defined scheme, but do
				 * not compact the array.
				 */
				if (definedSchemes != null) {
					boolean found = false;
					for (int i = 0; i < definedSchemeCount; i++) {
						if (scheme == definedSchemes[i]) {
							found = true;
						}
						if (found) {
							if (i + 1 < definedSchemes.length) {
								definedSchemes[i] = definedSchemes[i + 1];
							} else {
								definedSchemes[i] = null;
							}
						}
					}
					if (found) {
						definedSchemeCount--;
					}
				}
				
				if (activeScheme == scheme) {
					activeScheme = null;
					activeSchemeIds = null;
					activeSchemeChanged = true;

					// Clear the binding solution.
					clearSolution();
				}
			}

			fireBindingManagerChanged(new BindingManagerEvent(this, false,
					activeSchemeChanged, scheme, schemeIdAdded,
					!schemeIdAdded, false, false));
		}
	}

	/**
	 * Sets the active bindings and the prefix table. This ensures that the two
	 * values change at the same time, and that any listeners are notified
	 * appropriately.
	 * 
	 * @param activeBindings
	 *            This is a map of triggers ( <code>TriggerSequence</code>)
	 *            to command ids (<code>String</code>). This value will only
	 *            be <code>null</code> if the active bindings have not yet
	 *            been computed. Otherwise, this value may be empty.
	 * @param activeBindingsByCommandId
	 *            This is a map of command ids (<code>String</code>) to
	 *            triggers ( <code>TriggerSequence</code>). This value will
	 *            only be <code>null</code> if the active bindings have not
	 *            yet been computed. Otherwise, this value may be empty.
	 * @param prefixTable
	 *            A map of prefixes (<code>TriggerSequence</code>) to a map
	 *            of available completions (possibly <code>null</code>, which
	 *            means there is an exact match). The available completions is a
	 *            map of trigger (<code>TriggerSequence</code>) to command
	 *            identifier (<code>String</code>). This value may be
	 *            <code>null</code> if there is no existing solution.
	 */
	private final void setActiveBindings(final Map activeBindings,
			final Map activeBindingsByCommandId, final Map prefixTable) {
		this.activeBindings = activeBindings;
		this.activeBindingsByCommandId = activeBindingsByCommandId;
		this.prefixTable = prefixTable;

		fireBindingManagerChanged(new BindingManagerEvent(this, true, false,
				null, false, false, false, false));
	}

	/**
	 * <p>
	 * Selects one of the schemes as the active scheme. This scheme must be
	 * defined.
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the height of the context tree.
	 * </p>
	 * 
	 * @param scheme
	 *            The scheme to become active; must not be <code>null</code>.
	 * @throws NotDefinedException
	 *             If the given scheme is currently undefined.
	 */
	public final void setActiveScheme(final Scheme scheme)
			throws NotDefinedException {
		if (scheme == null) {
			throw new NullPointerException("Cannot activate a null scheme"); //$NON-NLS-1$
		}

		if ((scheme == null) || (!scheme.isDefined())) {
			throw new NotDefinedException("Cannot activate an undefined scheme"); //$NON-NLS-1$
		}

		if (Util.equals(activeScheme, scheme)) {
			return;
		}

		activeScheme = scheme;
		activeSchemeIds = getSchemeIds(activeScheme.getId());
		clearSolution();
		fireBindingManagerChanged(new BindingManagerEvent(this, false, true,
				null, false, false, false, false));
	}

	/**
	 * <p>
	 * Changes the set of bindings for this binding manager. Changing the set of
	 * bindings all at once ensures that: (1) duplicates are removed; and (2)
	 * avoids the possibility of unnecessary intermediate computations. This
	 * method clears the existing solution, but does not trigger a recomputation
	 * (other method calls are required to do that).
	 * </p>
	 * <p>
	 * This method completes in <code>O(n)</code>, where <code>n</code> is
	 * the number of bindings.
	 * </p>
	 * 
	 * @param bindings
	 *            The new array of bindings; may be <code>null</code>. This set
	 *            is copied into a local data structure.
	 */
	public final void setBindings(final Binding[] bindings) {
		if (Arrays.equals(this.bindings, bindings)) {
			return; // nothing has changed
		}

		if ((bindings == null) || (bindings.length == 0)) {
			this.bindings = null;
			bindingCount = 0;
		} else {
			final int bindingsLength = bindings.length;
			this.bindings = new Binding[bindingsLength];
			System.arraycopy(bindings, 0, this.bindings, 0, bindingsLength);
			bindingCount = bindingsLength;
		}
		clearCache();
	}

	/**
	 * <p>
	 * Changes the locale for this binding manager. The locale can be used to
	 * provide locale-specific bindings. If the locale is different than the
	 * current locale, then this will force a recomputation of the bindings. The
	 * locale is in the same format as
	 * <code>Locale.getDefault().toString()</code>.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @param locale
	 *            The new locale; must not be <code>null</code>.
	 * @see Locale#getDefault()
	 */
	public final void setLocale(final String locale) {
		if (locale == null) {
			throw new NullPointerException("The locale cannot be null"); //$NON-NLS-1$
		}

		if (!Util.equals(this.locale, locale)) {
			this.locale = locale;
			this.locales = expand(locale, LOCALE_SEPARATOR);
			clearSolution();
			fireBindingManagerChanged(new BindingManagerEvent(this, false,
					false, null, false, false, true, false));
		}
	}

	/**
	 * <p>
	 * Changes the platform for this binding manager. The platform can be used
	 * to provide platform-specific bindings. If the platform is different than
	 * the current platform, then this will force a recomputation of the
	 * bindings. The locale is in the same format as
	 * <code>SWT.getPlatform()</code>.
	 * </p>
	 * <p>
	 * This method completes in <code>O(1)</code>.
	 * </p>
	 * 
	 * @param platform
	 *            The new platform; must not be <code>null</code>.
	 * @see org.eclipse.swt.SWT#getPlatform()
	 */
	public final void setPlatform(final String platform) {
		if (platform == null) {
			throw new NullPointerException("The platform cannot be null"); //$NON-NLS-1$
		}

		if (!Util.equals(this.platform, platform)) {
			this.platform = platform;
			this.platforms = expand(platform, Util.ZERO_LENGTH_STRING);
			clearSolution();
			fireBindingManagerChanged(new BindingManagerEvent(this, false,
					false, null, false, false, false, true));
		}
	}
}
