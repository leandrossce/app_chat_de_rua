package com.capongasoftware.chat_de_rua;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Arrays;
import java.util.Locale;

/** An explicit app preference, independent of the phone's language. */
final class AppLanguage {
    static final String[] TAGS = {"pt-BR", "en-US", "de-DE", "zh-CN", "es-ES"};
    static final String[] NAMES = {"Português (Brasil)", "English (United States)",
            "Deutsch (Deutschland)", "简体中文（中国）", "Español (España)"};
    private static final String PREFERENCES = "app_language";
    private static final String KEY = "language_tag";

    static String saved(Context context) {
        String tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, null);
        return Arrays.asList(TAGS).contains(tag) ? tag : null;
    }

    static String current(Context context) {
        String saved = saved(context);
        if (saved != null) return saved;
        // Suggest the device language, but still ask on the first launch.
        LocaleList locales = context.getResources().getConfiguration().getLocales();
        for (int i = 0; i < locales.size(); i++) {
            Locale locale = locales.get(i);
            for (String tag : TAGS) {
                if (Locale.forLanguageTag(tag).getLanguage().equals(locale.getLanguage())) return tag;
            }
        }
        return TAGS[0];
    }

    static Configuration configuration(Context context, String tag) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(tag);
        config.setLocales(new LocaleList(locale));
        config.setLayoutDirection(locale);
        return config;
    }

    static Context wrap(Context context) {
        return context.createConfigurationContext(configuration(context, current(context)));
    }

    static void save(Context context, String tag) {
        if (!Arrays.asList(TAGS).contains(tag)) throw new IllegalArgumentException("Unsupported locale");
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply();
    }

    @SuppressWarnings("deprecation")
    static void apply(Context context) {
        // Update this Activity's resources in place: recreation would destroy the live Bluetooth chat.
        context.getResources().updateConfiguration(configuration(context, current(context)),
                context.getResources().getDisplayMetrics());
    }

    private AppLanguage() { }
}
