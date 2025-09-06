package com.hush.utils;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.RectF;
import android.util.Log;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DetectionModel {
    private Interpreter tflite;
    public static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.75f;
    private static final float IOU_THRESHOLD = 0.7f;
    private Context context;

    public DetectionModel(Context context){
        this.context = context;
        try {
            tflite = new Interpreter(loadModelFile(context.getAssets(), "model.tflite"));
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(null, "Model couldn't be loaded!", Toast.LENGTH_LONG).show();
        }
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public boolean isDeviceDetected(ByteBuffer inputBuffer, int width, int height){
        float[][][] output = new float[1][5][8400];
        tflite.run(inputBuffer, output);

        List<RectF> boxes = postProcessDetections(output, width, height);

        if (boxes.isEmpty()) {
            return false;
        } else {
            return true;
        }
    }

    private List<RectF> postProcessDetections(float[][][] output, int originalWidth, int originalHeight) {
        List<RectF> boxes = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();

        for (int i = 0; i < 8400; i++) {
            float xCenter = output[0][0][i];
            float yCenter = output[0][1][i];
            float width = output[0][3][i];
            float height = output[0][2][i];
            float objectness = output[0][4][i];

            if (objectness > CONFIDENCE_THRESHOLD) {
                // Scale back to original image size
                xCenter *= originalWidth;
                yCenter *= originalHeight;
                width *= originalWidth;
                height *= originalHeight;

                float left = xCenter - width / 2;
                float top = yCenter - height / 2;
                float right = xCenter + width / 2;
                float bottom = yCenter + height / 2;

                boxes.add(new RectF(left, top, right, bottom));
                confidences.add(objectness);
            }
        }

        // Apply simple NMS
        List<Integer> selectedIndices = nms(boxes, confidences, IOU_THRESHOLD);

        List<RectF> finalBoxes = new ArrayList<>();
        for (Integer index : selectedIndices) {
            finalBoxes.add(boxes.get(index));
        }

        return finalBoxes;
    }

    private List<Integer> nms(List<RectF> boxes, List<Float> scores, float iouThreshold) {
        List<Integer> indices = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) order.add(i);

        Collections.sort(order, (i, j) -> Float.compare(scores.get(j), scores.get(i)));

        boolean[] suppressed = new boolean[scores.size()];

        for (int _i = 0; _i < order.size(); _i++) {
            int i = order.get(_i);
            if (suppressed[i]) continue;
            indices.add(i);
            for (int _j = _i + 1; _j < order.size(); _j++) {
                int j = order.get(_j);
                if (iou(boxes.get(i), boxes.get(j)) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return indices;
    }

    private float iou(RectF a, RectF b) {
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);

        float intersectionLeft = Math.max(a.left, b.left);
        float intersectionTop = Math.max(a.top, b.top);
        float intersectionRight = Math.min(a.right, b.right);
        float intersectionBottom = Math.min(a.bottom, b.bottom);

        float intersectionArea = Math.max(0, intersectionRight - intersectionLeft) * Math.max(0, intersectionBottom - intersectionTop);

        return intersectionArea / (areaA + areaB - intersectionArea);
    }
}
