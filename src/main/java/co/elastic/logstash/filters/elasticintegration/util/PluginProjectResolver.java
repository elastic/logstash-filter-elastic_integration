package co.elastic.logstash.filters.elasticintegration.util;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.core.CheckedRunnable;

public class PluginProjectResolver implements ProjectResolver {
    @Override
    public ProjectId getProjectId() {
        return null;
    }

    @Override
    public <E extends Exception> void executeOnProject(ProjectId projectId, CheckedRunnable<E> checkedRunnable) throws E {
        if (projectId.equals(ProjectId.DEFAULT)) {
            checkedRunnable.run();
        } else {
            throw new IllegalArgumentException("Cannot execute on a project other than [" + ProjectId.DEFAULT + "]");
        }
    }
}
