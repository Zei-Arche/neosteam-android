package org.neosteam.revival;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class HandshakeOverlay {
    private static final String REPORT_NAME = "handshake-result.json";
    private static final int MAX_REPORT_BYTES = 64 * 1024;
    private static final int VISIBLE_ROUND_TRIPS = 4;
    private static final String TITLE = "Server handshake";
    private static final String UNAVAILABLE = TITLE
        + "\nLive unavailable"
        + "\nProtocol adapter missing"
        + "\nConnect / packet RTT / coordinates: unavailable";

    private HandshakeOverlay() {}

    static View wrap(Activity activity, View content) {
        FrameLayout root = new FrameLayout(activity);
        root.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView panel = new TextView(activity);
        panel.setText(UNAVAILABLE);
        panel.setTextColor(Color.WHITE);
        panel.setTextSize(12);
        panel.setTypeface(Typeface.MONOSPACE);
        panel.setMaxWidth(dp(activity, 420));
        int padding = dp(activity, 8);
        panel.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xe620252a);
        background.setCornerRadius(padding);
        panel.setBackground(background);
        panel.setVisibility(View.GONE);

        LinearLayout diagnostics = new LinearLayout(activity);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.setGravity(Gravity.END);
        Button toggle = new Button(activity);
        toggle.setAllCaps(false);
        toggle.setText("Handshake");
        toggle.setMinHeight(dp(activity, 48));
        toggle.setOnClickListener(view -> {
            boolean opening = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(opening ? View.VISIBLE : View.GONE);
            toggle.setSelected(opening);
        });
        diagnostics.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        diagnostics.addView(panel, new LinearLayout.LayoutParams(-2, -2));

        int margin = dp(activity, 12);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END);
        position.setMargins(margin, margin, margin, margin);
        root.addView(diagnostics, position);
        diagnostics.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(view, insets, margin);
            return insets;
        });

        Thread reader = new Thread(() -> {
            File report = new File(activity.getFilesDir(), REPORT_NAME);
            if (report.exists()) {
                String summary = summarize(report);
                panel.post(() -> {
                    if (!activity.isDestroyed() && !activity.isFinishing()) {
                        panel.setText(summary);
                    }
                });
            }
        }, "NeoSteam handshake report");
        reader.start();
        return root;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private static void applyInsets(View view, WindowInsets insets, int margin) {
        int left = insets.getSystemWindowInsetLeft();
        int top = insets.getSystemWindowInsetTop();
        int right = insets.getSystemWindowInsetRight();
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout() | WindowInsets.Type.systemGestures());
            left = safe.left;
            top = safe.top;
            right = safe.right;
        }
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (layout.leftMargin != left + margin || layout.topMargin != top + margin
                || layout.rightMargin != right + margin) {
            layout.setMargins(left + margin, top + margin, right + margin, margin);
            view.setLayoutParams(layout);
        }
    }

    private static String summarize(File file) {
        try {
            return formatReport(new JSONObject(readReport(file)));
        } catch (IOException | JSONException invalid) {
            return TITLE + "\nReport unavailable\nInvalid or unreadable report";
        }
    }

    private static String readReport(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (data.size() + count > MAX_REPORT_BYTES) {
                    throw new IOException("Handshake report exceeds size limit");
                }
                data.write(chunk, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data.toByteArray())).toString();
        }
    }

    private static String formatReport(JSONObject report) throws JSONException {
        if (number(report.get("version")) != 1) {
            throw new JSONException("Unsupported handshake report version");
        }
        String source = switch (field(report, "source")) {
            case "fixture_replay" -> "Fixture replay";
            case "live" -> "Live report";
            default -> throw new JSONException("Invalid handshake report source");
        };
        String status = field(report, "status");
        if (!status.equals("passed") && !status.equals("failed") && !status.equals("blocked")) {
            throw new JSONException("Invalid handshake report status");
        }
        StringBuilder output = new StringBuilder(TITLE).append('\n')
            .append(source).append(" | ").append(status).append(" | ").append(field(report, "stage"));
        if (report.has("reason")) {
            output.append('\n').append(field(report, "reason"));
        }
        output.append("\nConnect: ").append(report.has("connectMs")
            ? milliseconds(report.get("connectMs")) : "unavailable");

        JSONArray roundTrips = report.getJSONArray("roundTrips");
        output.append("\nPacket RTT (").append(roundTrips.length()).append("):");
        if (roundTrips.length() == 0) {
            output.append(" unavailable");
        } else {
            int firstVisible = Math.max(0, roundTrips.length() - VISIBLE_ROUND_TRIPS);
            if (firstVisible > 0) {
                output.append("\n").append(firstVisible).append(" earlier");
            }
            for (int index = 0; index < roundTrips.length(); ++index) {
                JSONObject trip = roundTrips.getJSONObject(index);
                String summary = field(trip, "stage") + " " + opcode(trip.get("requestOpcode")) + " -> "
                    + opcode(trip.get("responseOpcode")) + ", " + milliseconds(trip.get("elapsedMs"));
                if (index >= firstVisible) {
                    output.append('\n').append(summary);
                }
            }
        }

        if (report.has("coordinates")) {
            JSONObject coordinates = report.getJSONObject("coordinates");
            boolean xyOnly = report.has("coordinateAxes")
                && field(report, "coordinateAxes").equals("XY");
            output.append("\nEntity: ").append(field(coordinates, "entityId"))
                .append("\nServer: ").append(position(coordinates.getJSONArray("server"), xyOnly))
                .append("\nScene:  ").append(position(coordinates.getJSONArray("scene"), xyOnly))
                .append(String.format(Locale.ROOT, "\nMax error: %.5g / %.5g",
                    nonNegative(coordinates.get("maxAbsError")),
                    nonNegative(coordinates.get("tolerance"))));
        } else {
            output.append("\nScene coordinates: unavailable");
        }
        return output.toString();
    }

    private static String field(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String text)) {
            throw new JSONException("Expected a text field");
        }
        String line = text.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").trim();
        if (line.isEmpty()) {
            throw new JSONException("Expected a nonempty text field");
        }
        int end = line.offsetByCodePoints(0, Math.min(line.codePointCount(0, line.length()), 96));
        return line.substring(0, end);
    }

    private static double number(Object value) throws JSONException {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new JSONException("Expected a finite number");
        }
        return number.doubleValue();
    }

    private static double nonNegative(Object value) throws JSONException {
        double result = number(value);
        if (result < 0) {
            throw new JSONException("Expected a nonnegative number");
        }
        return result;
    }

    private static String milliseconds(Object value) throws JSONException {
        return String.format(Locale.ROOT, "%.3f ms", nonNegative(value));
    }

    private static String opcode(Object value) throws JSONException {
        double result = nonNegative(value);
        if (result > 0xffff_ffffL || result != Math.rint(result)) {
            throw new JSONException("Invalid opcode");
        }
        return String.format(Locale.ROOT, "0x%04X", (long) result);
    }

    private static String position(JSONArray coordinates, boolean xyOnly) throws JSONException {
        if (coordinates.length() != 3) {
            throw new JSONException("Expected XYZ coordinates");
        }
        double x = number(coordinates.get(0));
        double y = number(coordinates.get(1));
        double z = number(coordinates.get(2));
        return xyOnly ? String.format(Locale.ROOT, "(%.5g, %.5g)", x, y)
            : String.format(Locale.ROOT, "(%.5g, %.5g, %.5g)", x, y, z);
    }
}
