package org.jenkinsci.plugins.workflow.steps;

import hudson.model.Result;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.ShellStep;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable.Row;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * @author Kohsuke Kawaguchi
 */
public class TimeoutStepTest extends Assert {
    @Rule
    public JenkinsRule r = new JenkinsRule();

    /**
     * The simplest possible timeout step ever.
     */
    @Test
    public void basic() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "import java.util.concurrent.*; node { timeout(time:5, unit:TimeUnit.SECONDS) { sh 'sleep 10'; echo 'NotHere' } }"));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        r.assertLogNotContains("NotHere", b);
    }

    @Test
    public void killingParallel() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(join(
                "import java.util.concurrent.*; node { ",
                    "timeout(time:5, unit:TimeUnit.SECONDS) { ",
                        "parallel(",
                            " a: { echo 'ShouldBeHere1'; sh 'sleep 10'; echo 'NotHere' }, ",
                            " b: { echo 'ShouldBeHere2'; sh 'sleep 10'; echo 'NotHere' }, ",
                        ");",
                        "echo 'NotHere'",
                    "}",
                    "echo 'NotHere'",
                "}")));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());

        // make sure things that are supposed to run do, and things that are NOT supposed to run do not.
        r.assertLogNotContains("NotHere", b);
        r.assertLogContains("ShouldBeHere1",b);
        r.assertLogContains("ShouldBeHere2", b);

        // we expect every shell step to have failed
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        for (Row r : t.getRows()) {
            if (r.getNode() instanceof StepAtomNode) {
                StepAtomNode a = (StepAtomNode) r.getNode();
                if (a.getDescriptor().getClass()==ShellStep.DescriptorImpl.class) {
                    assertTrue(a.getAction(ErrorAction.class)!=null);
                }
            }
        }
    }

    // TODO: timeout inside parallel

    public String join(String... args) {
        return StringUtils.join(args, "\n");
    }
}
