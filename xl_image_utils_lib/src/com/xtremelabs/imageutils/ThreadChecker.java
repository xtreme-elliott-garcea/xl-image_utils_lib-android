package com.xtremelabs.imageutils;

import android.os.Looper;

class ThreadChecker {
	private static boolean mDisableUiThreadCheck = false;

	public static boolean isOnUiThread() {
		return Looper.getMainLooper().getThread() == Thread.currentThread();
	}

	public static void throwErrorIfOffUiThread() {
		if (!isOnUiThread() && !mDisableUiThreadCheck) {
			throw new CalledFromWrongThreadException("This method needs to be called from the UI thread!");
		}
	}

	public static class CalledFromWrongThreadException extends RuntimeException {
		public CalledFromWrongThreadException(String errorMessage) {
			super(errorMessage);
		}

		private static final long serialVersionUID = 6185062163857001930L;
	}

	static void enableUiThreadCheck() {
		mDisableUiThreadCheck = false;
	}

	static void disableUiThreadCheck() {
		mDisableUiThreadCheck = true;
	}
}
