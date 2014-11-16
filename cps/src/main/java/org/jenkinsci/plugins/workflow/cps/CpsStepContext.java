/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.cps;

import com.cloudbees.groovy.cps.Outcome;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import groovy.lang.Closure;
import hudson.model.Descriptor;
import hudson.model.Result;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.cps.persistence.PersistIn;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.WorkflowBodyInvoker;
import org.jenkinsci.plugins.workflow.support.DefaultStepContext;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

import static org.jenkinsci.plugins.workflow.cps.persistence.PersistenceContext.*;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;

/**
 * {@link StepContext} implementation for CPS.
 *
 * <p>
 * This context behaves in two modes. It starts in the synchronous mode, where if a result is set (or exception
 * is thrown), it just gets recoded. When passed into {@link Step#start(StepContext)}, it's in this mode.
 *
 * <p>
 * When {@link Step#start(StepContext)} method returns, we'll atomically check if the result is set or not
 * and then switch to the asynchronous mode. In this mode, if the result is set, it'll trigger the rehydration
 * of the workflow. If a {@link CpsStepContext} gets serialized, it'll be deserialized in the asynchronous mode.
 *
 * <p>
 * This object must be serializable on its own without sucking in any of the {@link CpsFlowExecution} object
 * graph. Wherever we need {@link CpsFlowExecution} we do that by following {@link FlowExecutionOwner}, and
 * when we need pointers to individual objects inside, we use IDs (such as {@link #id}}.
 *
 * @author Kohsuke Kawaguchi
 */
@PersistIn(ANYWHERE)
@edu.umd.cs.findbugs.annotations.SuppressWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED") // bodyInvokers, syncMode handled specially
public class CpsStepContext extends DefaultStepContext { // TODO add XStream class mapper

    private static final Logger LOGGER = Logger.getLogger(CpsStepContext.class.getName());

    // guarded by 'this'
    private transient Outcome outcome;

    // see class javadoc.
    // transient because if it's serialized and deserialized, it should come back in the async mode.
    private transient boolean syncMode = true;

    /**
     * This object gets serialized independently from the rest of {@link CpsFlowExecution}
     * and {@link DSL}, so it needs to use a handle to refer back to {@link CpsFlowExecution}
     */
    private final FlowExecutionOwner executionRef;

    /**
     * {@link FlowNode#id} that points to the atom node created for this step.
     */
    private final String id;

    /**
     * Keeps an in-memory reference to {@link FlowNode} to speed up the synchronous execution.
     *
     * If there's a body, this field is {@link BlockStartNode}. If there's no body, then this
     * field is {@link AtomNode}
     *
     * @see #getNode()
     */
    /*package*/ transient FlowNode node;

    /*

        TODO: parallel step implementation

        when forking off another branch of parallel, call the 3-arg version of the start() method,
        and have its callback insert the ID of the new head at the end of the thread
     */
    /**
     * {@link FlowNode#getId()}s keyed by {@link FlowHead#getId()} that should become
     * the parents of the {@link BlockEndNode} when we create one. Only used when this context has the body.
     */
    final Map<Integer,String> bodyInvHeads = new TreeMap<Integer,String>();

    /**
     * If the invocation of the body is requested, this object remembers how to start it.
     *
     * <p>
     * Only used in the synchronous mode while {@link CpsFlowExecution} is in the RUNNABLE state,
     * so this need not be persisted.
     */
    transient Set<CpsBodyInvoker> bodyInvokers = Collections.synchronizedSet(new HashSet<CpsBodyInvoker>());

    /**
     * While {@link CpsStepContext} has not received teh response, maintains the body closure.
     *
     * This is the implicit closure block passed to the step invocation.
     */
    private BodyReference body;

    private final int threadId;

    /**
     * {@linkplain Descriptor#getId() step descriptor ID}.
     */
    private final String stepDescriptorId;

    /**
     * Resolved result of {@link #stepDescriptorId} to make the look up faster.
     */
    private transient volatile StepDescriptor stepDescriptor;

    @CpsVmThreadOnly
    CpsStepContext(StepDescriptor step, CpsThread thread, FlowExecutionOwner executionRef, FlowNode node, Closure body) {
        this.threadId = thread.id;
        this.executionRef = executionRef;
        this.id = node.getId();
        this.node = node;
        this.body = thread.group.export(body);
        this.stepDescriptorId = step.getId();
    }

    /**
     * Obtains {@link StepDescriptor} that represents the step this context is invoking.
     *
     * @return
     *      This method returns null if the step descriptor used is not recoverable in the current VM session,
     *      such as when the plugin that implements this was removed. So the caller should defend against null.
     */
    public @CheckForNull StepDescriptor getStepDescriptor() {
        Jenkins j = Jenkins.getInstance();
        if (j == null) {
            return null;
        }
        if (stepDescriptor==null)
            stepDescriptor = (StepDescriptor) j.getDescriptor(stepDescriptorId);
        return stepDescriptor;
    }

    public String getDisplayName() {
        StepDescriptor d = getStepDescriptor();
        return d!=null ? d.getDisplayName() : stepDescriptorId;
    }

    @Override protected CpsFlowExecution getExecution() throws IOException {
        return (CpsFlowExecution)executionRef.get();
    }

    @CheckForNull CpsThread getThread(CpsThreadGroup g) {
        CpsThread thread = g.threads.get(threadId);
        if (thread == null) {
            LOGGER.log(Level.FINE, "no thread " + threadId + " among " + g.threads.keySet(), new IllegalStateException());
        }
        return thread;
    }

    /**
     * Synchronously resolve the current thread.
     *
     * This can block for the entire duration of the PREPARING state.
     */
    @CheckForNull CpsThread getThreadSynchronously() throws InterruptedException, IOException {
        try {
            CpsThreadGroup g = getFlowExecution().programPromise.get();
            return getThread(g);
        } catch (ExecutionException e) {
            throw new IOException(e);
        }
    }

    @Override public boolean isReady() throws IOException, InterruptedException {
        ListenableFuture<CpsThreadGroup> p = getFlowExecution().programPromise;
        return p.isDone();
    }

    @Override
    public WorkflowBodyInvoker newBodyInvoker() {
        return newBodyInvoker(body);
    }

    public WorkflowBodyInvoker newBodyInvoker(BodyReference body) {
        if (body==null)
            throw new IllegalStateException("There's no body to invoke");

        final CpsBodyInvoker b = new CpsBodyInvoker(this,body);

        return b;
    }

    @Override
    protected <T> T doGet(Class<T> key) throws IOException, InterruptedException {
        CpsThread t = getThreadSynchronously();
        if (t == null) {
            throw new IOException("cannot find current thread");
        }

        T v = t.getContextVariable(key);
        if (v!=null)        return v;

        if (FlowNode.class.isAssignableFrom(key)) {
            return key.cast(getNode());
        }
        if (key == CpsThread.class) {
            return key.cast(t);
        }
        if (key == CpsThreadGroup.class) {
            return key.cast(t.group);
        }
        return null;
    }

    @Override protected FlowNode getNode() throws IOException {
        if (node == null) {
            node = getFlowExecution().getNode(id);
            if (node == null) {
                throw new IOException("no node found for " + id);
            }
        }
        return node;
    }

    public synchronized void onFailure(Throwable t) {
        if (t==null)
            throw new IllegalArgumentException();
        if (isCompleted())
            throw new IllegalStateException("Already completed", t);
        this.outcome = new Outcome(null,t);

        scheduleNextRun();
    }

    public synchronized void onSuccess(Object returnValue) {
        if (isCompleted())
            throw new IllegalStateException("Already completed");
        this.outcome = new Outcome(returnValue,null);

        scheduleNextRun();
    }

    /**
     * When this step context has completed execution (successful or otherwise), plan the next action.
     */
    private void scheduleNextRun() {
        if (!syncMode) {
            try {
                final FlowNode n = getNode();
                final CpsFlowExecution flow = getFlowExecution();

                final List<FlowNode> parents = new ArrayList<FlowNode>();
                parents.add(null);      // make room for the primary head
                for (String head : bodyInvHeads.values()) {
                    parents.add(flow.getNode(head));
                }

                flow.runInCpsVmThread(new FutureCallback<CpsThreadGroup>() {
                    @CpsVmThreadOnly
                    @Override
                    public void onSuccess(CpsThreadGroup g) {
                        g.unexport(body);
                        body = null;
                        CpsThread thread = getThread(g);
                        if (thread != null) {
                            if (n instanceof StepStartNode) {
                                FlowNode tip = thread.head.get();
                                parents.set(0, tip);

                                thread.head.setNewHead(new StepEndNode(flow, (StepStartNode) n, parents));
                            }
                            thread.head.markIfFail(getOutcome());
                            thread.setStep(null);
                            thread.resume(getOutcome());
                        }
                    }

                    /**
                     * Program state failed to load.
                     */
                    @Override
                    public void onFailure(Throwable t) {
                    }
                });
            } catch (IOException x) {
                LOGGER.log(Level.FINE, null, x);
            }
        }
    }

    @Override
    public void setResult(Result r) {
        try {
            getFlowExecution().setResult(r);
        } catch (IOException x) {
            LOGGER.log(Level.FINE, null, x);
        }
    }

    private @Nonnull CpsFlowExecution getFlowExecution() throws IOException {
        return (CpsFlowExecution)executionRef.get();
    }

    synchronized boolean isCompleted() {
        return outcome!=null;
    }

    synchronized boolean isSyncMode() {
        return syncMode;
    }

    /**
     * Simulates the result of the {@link StepContext call} by either throwing an exception
     * or returning the value.
     */
    synchronized Object replay() {
        try {
            return getOutcome().replay();
        } catch (Throwable failure) {
            if (failure instanceof RuntimeException)
                throw (RuntimeException) failure;
            if (failure instanceof Error)
                throw (Error) failure;
            throw new UndeclaredThrowableException(failure);
        }
    }

    synchronized Outcome getOutcome() {
        return outcome;
    }

    /**
     * Atomically switch this context into the asynchronous mode.
     * Any results set beyond this point will trigger callback.
     *
     * @return
     *      true if the result was not available prior to this call and the context was successfully switched to the
     *      async mode.
     *
     *      false if the result is already available. The caller should use {@link #getOutcome()} to obtain that.
     */
    synchronized boolean switchToAsyncMode() {
        if (!syncMode)  throw new AssertionError();
        syncMode = false;
        return !isCompleted();
    }

    @Override public ListenableFuture<Void> saveState() {
        try {
            final SettableFuture<Void> f = SettableFuture.create();
            getFlowExecution().runInCpsVmThread(new FutureCallback<CpsThreadGroup>() {
                @Override public void onSuccess(CpsThreadGroup result) {
                    try {
                        // TODO keep track of whether the program was saved anyway after saveState was called but before now, and do not bother resaving it in that case
                        result.saveProgram();
                        f.set(null);
                    } catch (IOException x) {
                        f.setException(x);
                    }
                }
                @Override public void onFailure(Throwable t) {
                    f.setException(t);
                }
            });
            return f;
        } catch (IOException x) {
            return Futures.immediateFailedFuture(x);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CpsStepContext that = (CpsStepContext) o;

        return executionRef.equals(that.executionRef) && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        int result = executionRef.hashCode();
        result = 31 * result + id.hashCode();
        return result;
    }

    private static final long serialVersionUID = 1L;
}
