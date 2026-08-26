package com.applyflow.validation;

public final class RequestLimits {

    public static final int TITLE = 180;
    public static final int URL = 1000;
    public static final int LOCATION = 160;
    public static final int COMPANY_NAME = 160;
    public static final int FULL_NAME = 160;
    public static final int WEBSITE = 500;
    public static final int INDUSTRY = 120;
    public static final int TECHNOLOGY_NAME = 100;
    public static final int EMAIL = 254;
    public static final int TOKEN = 256;
    public static final int PROVIDER_USER_ID = 255;
    public static final int PASSWORD = 72;
    public static final int NOTES = 5000;
    public static final int SALARY = 20;
    public static final int RAW_CURRENCY = 16;
    public static final int TECHNOLOGIES = 50;
    public static final int STATUSES = 9;

    private RequestLimits() {
    }

    public static boolean exceedsCodePoints(String value, int maximum) {
        return value != null && value.codePointCount(0, value.length()) > maximum;
    }

    public static String truncateCodePoints(String value, int maximum) {
        int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), maximum));
        return value.substring(0, end);
    }
}
