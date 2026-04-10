package cn.classfun.droidvm.lib.ui;

import android.view.MotionEvent;

import androidx.appcompat.app.AppCompatActivity;

public abstract class SwipeableTabActivity
    extends AppCompatActivity
    implements TabSwipeHelper.Callback {
    protected TabSwipeHelper swipeHelper;

    protected void initSwipeHelper() {
        swipeHelper = new TabSwipeHelper(this, this);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeHelper != null &&
            swipeHelper.onDispatchTouchEvent(ev, super::dispatchTouchEvent)
        ) return true;
        return super.dispatchTouchEvent(ev);
    }
}
