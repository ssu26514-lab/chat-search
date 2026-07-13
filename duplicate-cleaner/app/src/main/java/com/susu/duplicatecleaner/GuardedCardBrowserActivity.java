package com.susu.duplicatecleaner;

import android.os.Bundle;
import android.widget.Toast;

public class GuardedCardBrowserActivity extends CardBrowserV2Activity {
    private boolean sessionAcquired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.BROWSER);
        super.onCreate(savedInstanceState);
        if (!sessionAcquired) {
            Toast.makeText(this, "另一个文件工具仍在运行，请先退出后再使用。", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.BROWSER);
        super.onDestroy();
    }
}
