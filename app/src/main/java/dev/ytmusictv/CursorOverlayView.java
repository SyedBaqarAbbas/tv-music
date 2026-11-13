package dev.ytmusictv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A passive cursor with no bitmap, animator, timer, or input interception. */
final class CursorOverlayView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float size;
    private float cursorX;
    private float cursorY;

    CursorOverlayView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        size = 21f * density;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 16f * density);
        arrow.lineTo(4.5f * density, 12f * density);
        arrow.lineTo(8f * density, 20f * density);
        arrow.lineTo(11f * density, 18.5f * density);
        arrow.lineTo(8f * density, 11f * density);
        arrow.lineTo(15f * density, 11f * density);
        arrow.close();
        fill.setColor(Color.rgb(242, 238, 230));
        fill.setStyle(Paint.Style.FILL);
        outline.setColor(Color.rgb(16, 17, 22));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.6f * density);
        outline.setStrokeJoin(Paint.Join.ROUND);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setLongClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(View.INVISIBLE);
    }

    void setPosition(float x, float y) {
        invalidateRegion(cursorX, cursorY);
        cursorX = x;
        cursorY = y;
        invalidateRegion(cursorX, cursorY);
    }

    private void invalidateRegion(float x, float y) {
        int padding = (int) Math.ceil(size + outline.getStrokeWidth());
        invalidate((int) x - padding, (int) y - padding,
                (int) x + padding + 1, (int) y + padding + 1);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int saved = canvas.save();
        canvas.translate(cursorX, cursorY);
        // Keep the arrow visible while its tip can reach every edge of the page.
        canvas.scale(cursorX > getWidth() - size ? -1f : 1f,
                cursorY > getHeight() - size ? -1f : 1f);
        canvas.drawPath(arrow, outline);
        canvas.drawPath(arrow, fill);
        canvas.restoreToCount(saved);
    }
}
