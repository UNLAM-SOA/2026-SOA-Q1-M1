package com.unlam.pawgate;

import android.content.Context;

public final class UserInterfaceHelper {
    private UserInterfaceHelper() {
    }

    public static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
