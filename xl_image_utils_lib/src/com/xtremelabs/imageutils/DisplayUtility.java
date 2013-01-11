/*
 * Copyright 2013 Xtreme Labs
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xtremelabs.imageutils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

public class DisplayUtility {
	@SuppressLint("NewApi")
	@SuppressWarnings("deprecation")
	public static Dimensions getDisplaySize(Context applicationContext) {
		Display display = ((WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		Dimensions displaySize;

		if (Build.VERSION.SDK_INT < 13) {
			// These method calls are used before API level 13.
			displaySize = new Dimensions(display.getWidth(), display.getHeight());
		} else {
			Point size = new Point();
			display.getSize(size);
			displaySize = new Dimensions(size.x, size.y);
		}

		return displaySize;
	}
}
