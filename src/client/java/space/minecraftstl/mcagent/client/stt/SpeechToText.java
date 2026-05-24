package space.minecraftstl.mcagent.client.stt;

import space.minecraftstl.mcagent.client.config.McagentConfig;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

public class SpeechToText {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern QWEN_PATTERN = Pattern.compile("^[Qq]wen3(\\.[0-9])?(-.*)?$|^[Qq]wen3-asr.*$");

    public static boolean isQwenModel(String model) {
        return model != null && QWEN_PATTERN.matcher(model).matches();
    }

    public static String transcribe(byte[] wavData) {
        McagentConfig cfg = McagentConfig.get();
        if ("local".equals(cfg.sttType)) {
            return transcribeLocal(wavData, cfg);
        }
        if (isQwenModel(cfg.sttModel)) {
            // Qwen模型走WebSocket实时ASR（直连DashScope，不走中转站）
            return QwenRealtimeAsr.transcribeBlocking(wavData, cfg);
        }
        // 非Qwen模型用OpenAI标准multipart格式
        return transcribeOnline(wavData, cfg);
    }

    private static String transcribeOnline(byte[] wavData, McagentConfig cfg) {
        try {
            String boundary = UUID.randomUUID().toString();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String nl = "\r\n";
            body.write(("--" + boundary + nl).getBytes());
            body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"" + nl).getBytes());
            body.write(("Content-Type: audio/wav" + nl + nl).getBytes());
            body.write(wavData);
            body.write(nl.getBytes());
            body.write(("--" + boundary + nl).getBytes());
            body.write(("Content-Disposition: form-data; name=\"model\"" + nl + nl).getBytes());
            body.write(cfg.sttModel.getBytes());
            body.write(nl.getBytes());
            body.write(("--" + boundary + "--" + nl).getBytes());
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.sttApiUrl))
                    .header("Authorization", "Bearer " + cfg.sttApiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.out.println("[mcAgent] STT请求失败: status=" + resp.statusCode() + " body=" + resp.body());
                return "[STT错误: HTTP " + resp.statusCode() + "]";
            }
            String respBody = resp.body();
            int idx = respBody.indexOf("\"text\"");
            if (idx >= 0) {
                int start = respBody.indexOf("\"", idx + 6);
                int end = respBody.indexOf("\"", start + 1);
                if (start >= 0 && end > start) return respBody.substring(start + 1, end);
            }
            return respBody;
        } catch (Exception e) {
            return "[STT错误: " + e.getMessage() + "]";
        }
    }

    private static String transcribeLocal(byte[] wavData, McagentConfig cfg) {
        try {
            File tmp = File.createTempFile("mcagent_", ".wav");
            tmp.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(wavData);
            }
            ProcessBuilder pb = new ProcessBuilder(cfg.sttLocalPath, tmp.getAbsolutePath(), "--output-txt");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            tmp.delete();
            return output;
        } catch (Exception e) {
            return "[本地STT错误: " + e.getMessage() + "]";
        }
    }
}