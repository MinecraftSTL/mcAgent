package space.minecraftstl.mcagent.client.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandFeedbackCapture {
    private static volatile boolean capturing = false;
    private static final List<String> captured = Collections.synchronizedList(new ArrayList<>());

    public static void startCapture() {
        captured.clear();
        capturing = true;
    }

    public static List<String> stopCapture() {
        capturing = false;
        List<String> result = new ArrayList<>(captured);
        captured.clear();
        return result;
    }

    public static boolean isCapturing() {
        return capturing;
    }

    public static void onSystemMessage(String message) {
        if (capturing && !message.contains("[AI]")) {
            captured.add(message);
        }
    }
}