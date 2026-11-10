package com.capongasoftware.chat_de_rua;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/** Resource identity travels with UI text; user messages and input are never translated. */
final class LocalizedText {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private static final class Resource {
        final int id;
        final int quantity;
        final Object[] arguments;

        Resource(int id, int quantity, Object[] arguments) {
            this.id = id;
            this.quantity = quantity;
            this.arguments = arguments.clone();
        }

        CharSequence render(Context context) {
            Object[] translated = arguments.clone();
            for (int i = 0; i < translated.length; i++) {
                if (translated[i] instanceof CharSequence) {
                    translated[i] = refresh(context, (CharSequence) translated[i]);
                }
            }
            String value = quantity < 0 ? (translated.length == 0 ? context.getString(id)
                    : context.getString(id, translated))
                    : context.getResources().getQuantityString(id, quantity, translated);
            SpannableString text = new SpannableString(value);
            text.setSpan(this, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return text;
        }
    }

    static CharSequence text(Context context, int id, Object... arguments) {
        return new Resource(id, -1, arguments).render(context);
    }

    static CharSequence plural(Context context, int id, int quantity, Object... arguments) {
        return new Resource(id, quantity, arguments).render(context);
    }

    static CharSequence refresh(Context context, CharSequence value) {
        if (value instanceof Spanned) {
            Resource[] resources = ((Spanned) value).getSpans(0, value.length(), Resource.class);
            if (resources.length == 1) return resources[0].render(context);
        }
        return value;
    }

    static void bindAttributes(View view, AttributeSet attrs) {
        int description = attrs.getAttributeResourceValue(ANDROID, "contentDescription", 0);
        if (description != 0) view.setContentDescription(text(view.getContext(), description));
        if (!(view instanceof TextView)) return;
        TextView label = (TextView) view;
        int text = attrs.getAttributeResourceValue(ANDROID, "text", 0);
        int hint = attrs.getAttributeResourceValue(ANDROID, "hint", 0);
        if (text != 0) label.setText(text(view.getContext(), text));
        if (hint != 0) label.setHint(text(view.getContext(), hint));
    }

    static void refreshViews(View view, Context context) {
        view.setContentDescription(refresh(context, view.getContentDescription()));
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            // Even a message identical to an app label remains untouched (no resource span).
            if (!(label instanceof EditText)) {
                CharSequence old = label.getText();
                CharSequence updated = refresh(context, old);
                if (old != updated) label.setText(updated);
            }
            label.setHint(refresh(context, label.getHint()));
            if (label.getError() != null) label.setError(refresh(context, label.getError()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) refreshViews(group.getChildAt(i), context);
        }
    }

    private LocalizedText() { }
}
