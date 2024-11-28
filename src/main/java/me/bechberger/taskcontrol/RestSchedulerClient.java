package me.bechberger.taskcontrol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** A thin client to interact with the {@link SchedulerServer} */
public class RestSchedulerClient {

    private final int port;
    private final HttpClient client = HttpClient.newHttpClient();

    public RestSchedulerClient(int port) {
        this.port = port;
        checkConnection();
    }

    private void checkConnection() {
        try {
            var request = HttpRequest.newBuilder().uri(url("help", Map.of())).GET().build();
            client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalArgumentException("Server does not return expected help message, maybe it hasn't been started?");
        }
    }

    private URI url(String path, Map<String, Object> urlParameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("http://localhost:")
                .append(port)
                .append("/")
                .append(path);
        if (!urlParameters.isEmpty()) {
            sb.append("?");
            for (Map.Entry<String, Object> entry : urlParameters.entrySet()) {
                sb.append(entry.getKey())
                        .append("=")
                        .append(entry.getValue())
                        .append("&");
            }
            sb.deleteCharAt(sb.length() - 1);
        }
        return URI.create(sb.toString());
    }

    private String request(String path, Map<String, Object> urlParameters) {
        var request = HttpRequest.newBuilder().uri(url(path, urlParameters)).GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Scheduling status of a thread or task */
    public enum TaskStatus {
        /** The task can be rescheduled */
        RUNNING,
        /** The task cannot be rescheduled */
        STOPPED,
        /** No setting given, so it can be rescheduled */
        UNKNOWN;

        static TaskStatus fromString(String status) {
            return switch (status) {
                case "running" -> RUNNING;
                case "stopping" -> STOPPED;
                default -> UNKNOWN;
            };
        }
    }

    public TaskStatus getTaskStatus(long taskId) {
        var response = request("task/" + taskId, Map.of());
        return TaskStatus.fromString(response);
    }

    public void stop(long taskId) {
        request("task/" + taskId, Map.of("stopping", true));
    }

    public void resume(long taskId) {
        request("task/" + taskId, Map.of("stopping", false));
    }

    public TaskStatus getTaskGroupStatus(long groupId) {
        var response = request("taskGroup/" + groupId, Map.of());
        return TaskStatus.fromString(response);
    }

    public void stopGroup(long groupId) {
        request("taskGroup/" + groupId, Map.of("stopping", true));
    }

    public void resumeGroup(long groupId) {
        request("taskGroup/" + groupId, Map.of("stopping", false));
    }
}
