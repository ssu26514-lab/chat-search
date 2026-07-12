package com.susu.duplicatecleaner;

import android.os.Bundle;
import android.widget.Toast;

public class GuardedCardRenamerActivity extends CardRenamerActivity {
    private boolean sessionAcquired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.RENAME);
        super.onCreate(savedInstanceState);
        if (!sessionAcquired) {
            Toast.makeText(this, "重复文件清理功能仍在运行，请先退出该功能。", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.RENAME);
        super.onDestroy();
    }
}
