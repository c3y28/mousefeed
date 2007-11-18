/*
 * Copyright (C) Heavy Lifting Software 2007.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html. If redistributing this code,
 * this entire header must remain intact.
 */
package com.mousefeed.eclipse;

import static org.apache.commons.lang.Validate.notNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IHandler;
import org.eclipse.jface.action.ExternalActionManager;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.ExternalActionManager.ICallback;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.SWTKeySupport;
import org.eclipse.jface.commands.ActionHandler;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.RetargetAction;
import org.eclipse.ui.activities.IActivityManager;
import org.eclipse.ui.keys.IBindingService;

/**
 * Finds a keyboard shortcut for the provided action.
 * Pure strategy. Contains the actual magic of finding a shortcut.
 * The logic was developed by trying different actions, debugging them and
 * finding shortcuts for those.
 *
 * @author Andriy Palamarchuk
 */
public class ActionAcceleratorFinder {
    /**
     * Searches for an action inside of
     * <code>org.eclipse.ui.actions.TextActionHandler</code> utility actions.
     */
    private final TextActionHandlerActionLocator actionSearcher =
            new TextActionHandlerActionLocator();

    /**
     * The binding service for the associated workbench.
     */
    private final IBindingService bindingService;

    /**
     * The activity manager for the associated workbench.
     */
    private final IActivityManager activityManager;

    /**
     * Creates new finder.
     */
    public ActionAcceleratorFinder() {
        bindingService =
            (IBindingService) getWorkbench().getAdapter(IBindingService.class);
        activityManager =
            getWorkbench().getActivitySupport().getActivityManager();
    }

    //RETURNCOUNT:OFF
    //clear, simple structure, leave extra returns
    /**
     * The main entry point of the class.
     * Searches for the action keyboard accelerator.
     * @param action the action to search accelerator for.
     * Not <code>null</code>.
     * @return the human-readable representation of action accelerator.
     */
    public String find(IAction action) {
        notNull(action);

        // from the action
        if (action.getAccelerator() != 0) {
            return keyToStr(action.getAccelerator());
        }

        // from the action definition
        {
            final String s = findForActionDefinition(
                    action.getActionDefinitionId());
            if (s != null) {
                return s;
            }
        }

        // retarget action?
        if (action instanceof RetargetAction) {
            final RetargetAction a = (RetargetAction) action;
            if (a.getActionHandler() != null) {
                return find(a.getActionHandler());
            }
        }

        // from the keyboard binding
        return findActionBinding(action);
    }
    //RETURNCOUNT:ON

    /**
     * Finds keyboard shortcut for the action definition id.
     * @param actionDefinitionId the action definition id to search accelerator
     * for. If blank, the method returns <code>null</code>
     * @return A human-readable string representation of the accelerator.
     * Is <code>null</code> if can't be found.
     */
    private String findForActionDefinition(String definitionId) {
        if (StringUtils.isBlank(definitionId)) {
            return null;
        }
        final ICallback callback =
                ExternalActionManager.getInstance().getCallback();
        return callback.getAcceleratorText(definitionId);
    }

    /**
     * Scans current bindings, returns a trigger sequence, associated with this
     * action. Uses heuristic to find an association.
     * Assumes that if a binding action and the provided action are the same,
     * if they have same class.
     * This logic can potentially fail if an action class is used for
     * different actions.
     * @param action the action to search trigger sequence for.
     * Returns <code>null</code> if the action is <code>null</code>.
     * @return the accelerator from the trigger sequence associated
     * with the action or <code>null</code> if such sequence was not found.
     */
    private String findActionBinding(IAction action) {
        if (action == null) {
            return null;
        }
        if (action instanceof RetargetAction) {
            return findActionBinding(
                    ((RetargetAction) action).getActionHandler());
        }

        return scanBindings(action);
    }

    /**
     * Scans bindings for the action.
     * @param action the action to scan bindings for.
     * Assumed not <code>null</code>.
     * @return the accelerator from the action trigger sequence.
     * <code>null</code> if the binding was not found.
     */
    @SuppressWarnings("unchecked")
    private String scanBindings(IAction action) {
        final Map matches = bindingService.getPartialMatches(
                KeySequence.getInstance());
        final Class<? extends IAction> actionClass = action.getClass();
        for (Object o : matches.keySet()) {
            final TriggerSequence triggerSequence = (TriggerSequence) o;
            final Binding binding = (Binding) matches.get(triggerSequence);
            final Command command =
                    binding.getParameterizedCommand().getCommand();
            final IHandler handler = getCommandHandler(command);
            if (!(handler instanceof ActionHandler)) {
                continue;
            }

            if (isCommandEnabled(command)) {
                final String accelerator = getFromBindingData(
                        action, actionClass, triggerSequence, handler);
                if (accelerator != null) {
                    return accelerator;
                }
            }
        }
        return null;
    }

    //RETURNCOUNT:OFF
    //clear, simple structure, leave extra returns
    /**
     * Gets accelerator for the bindings data.
     * @param action the action. Assumed not null.
     * @param actionClass effective action class. Assumed not null.
     * @param triggerSequence the binding trigger sequence. Assumed not null.
     * @param handler the action handler. Assumed not null. 
     * @return the accelerator if found, <code>null</code> otherwise.
     */
    private String getFromBindingData(
            IAction action, Class<? extends IAction> actionClass,
            TriggerSequence triggerSequence, IHandler handler) {
        final ActionHandler actionHandler = (ActionHandler) handler;
        final IAction boundAction = actionHandler.getAction();
        if (boundAction == null) {
            return null;
        }
        if (boundAction.getClass().equals(actionClass)) {
            return triggerSequence.toString();
        }
        if (!(boundAction instanceof RetargetAction)) {
            return null;
        }
        final IAction searchTarget =
                ((RetargetAction) boundAction).getActionHandler(); 
        if (searchTarget == null) {
            return null;
        }
        if (searchTarget.getClass().equals(actionClass)) {
            return triggerSequence.toString();
        }
        if (actionSearcher.isSearchable(searchTarget)) {
            final String id =
                    actionSearcher.findActionDefinitionId(action, searchTarget);
            return findForActionDefinition(id);
        }
        return null;
    }
    //RETURNCOUNT:ON

    /**
     * Converts the provided key combination code to accelerator.
     * @param accelerator The accelerator to convert; should be a valid SWT
     * accelerator value.
     * @return The equivalent key stroke; never <code>null</code>.
     */
    private String keyToStr(final int accelerator) {
        return
            SWTKeySupport.convertAcceleratorToKeyStroke(accelerator).format();
    }

    /**
     * Current workbench. Not <code>null</code>.
     */
    private IWorkbench getWorkbench() {
        return PlatformUI.getWorkbench();
    }

    /**
     * Retrieves command handler from a command.
     * @param command the command to retrieve the handler from.
     * Not <code>null</code>.
     * @return the handler. Returns <code>null</code>,
     * if can't retrieve a handler. 
     */
    private IHandler getCommandHandler(Command command) {
        try {
            final Method method = Command.class.getDeclaredMethod("getHandler");
            method.setAccessible(true);
            return (IHandler) method.invoke(command);
        } catch (SecurityException e) {
            // want to know when this happens
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            // should never happen
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            // should never happen
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            // should never happen
            throw new AssertionError(e);
        }
    }

    /**
     * Returns <code>true</code> if the command is defined and is enabled. 
     * @param command the command to check. Not <code>null</code>.
     */
    private boolean isCommandEnabled(Command command) {
        return command.isDefined()
                && activityManager.getIdentifier(command.getId()).isEnabled();
    }
}