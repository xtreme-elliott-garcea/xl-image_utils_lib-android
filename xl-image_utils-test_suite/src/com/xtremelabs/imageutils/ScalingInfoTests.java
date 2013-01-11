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

import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

import com.xtremelabs.imageutils.AbstractImageLoader.Options;
import com.xtremelabs.testactivity.MainActivity;

public class ScalingInfoTests extends ActivityInstrumentationTestCase2<MainActivity> {
	private ImageLoader mImageLoader;
	private Options options;
	private ScalingInfo scalingInfo;
	private ImageView imageView;

	public ScalingInfoTests() {
		super(MainActivity.class);
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		options = new Options();
		imageView = new ImageView(getActivity());
	}

	@UiThreadTest
	public void testOptionsOff() {
		mImageLoader = new ImageLoader(getActivity());

		options.autoDetectBounds = false;
		options.useScreenSizeAsBounds = false;
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(null, scalingInfo.width);
		assertNull(scalingInfo.height);
		assertNull(scalingInfo.sampleSize);
	}

	@UiThreadTest
	public void testScreenBounds() {
		mImageLoader = new ImageLoader(getActivity());
		options.autoDetectBounds = false;
		options.useScreenSizeAsBounds = true;
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertNotNull(scalingInfo.width);
		assertNotNull(scalingInfo.height);

		Dimensions screenDimensions = DisplayUtility.getDisplaySize(getActivity().getApplicationContext());
		options.useScreenSizeAsBounds = true;
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);

		assertNotNull(scalingInfo.width);
		assertNotNull(scalingInfo.height);
		assertNull(scalingInfo.sampleSize);

		assertEquals(screenDimensions.width, scalingInfo.width);
		assertEquals(screenDimensions.height, scalingInfo.height);
	}

	@UiThreadTest
	public void testAutoDetectBounds() {
		mImageLoader = new ImageLoader(getActivity());
		options.autoDetectBounds = true;
		options.useScreenSizeAsBounds = false;

		setParams(LayoutParams.WRAP_CONTENT, 100);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertNull(scalingInfo.width);
		assertEquals(100, scalingInfo.height.intValue());
		assertNull(scalingInfo.sampleSize);

		setParams(100, LayoutParams.WRAP_CONTENT);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(100, scalingInfo.width.intValue());
		assertNull(scalingInfo.height);
		assertNull(scalingInfo.sampleSize);

		setParams(100, 50);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(100, scalingInfo.width.intValue());
		assertEquals(50, scalingInfo.height.intValue());
		assertNull(scalingInfo.sampleSize);
	}

	@UiThreadTest
	public void testAutoDetectBoundsWithScreenSize() {
		mImageLoader = new ImageLoader(getActivity());
		options.autoDetectBounds = true;
		options.useScreenSizeAsBounds = true;

		setParams(LayoutParams.WRAP_CONTENT, 100);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(DisplayUtility.getDisplaySize(getActivity().getApplicationContext()).width.intValue(), scalingInfo.width.intValue());
		assertEquals(100, scalingInfo.height.intValue());
		assertNull(scalingInfo.sampleSize);

		setParams(100, LayoutParams.WRAP_CONTENT);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(100, scalingInfo.width.intValue());
		assertEquals(DisplayUtility.getDisplaySize(getActivity().getApplicationContext()).height.intValue(), scalingInfo.height.intValue());
		assertNull(scalingInfo.sampleSize);

		setParams(100, 50);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(100, scalingInfo.width.intValue());
		assertEquals(50, scalingInfo.height.intValue());
		assertNull(scalingInfo.sampleSize);

		setParams(50000, 50000);
		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(DisplayUtility.getDisplaySize(getActivity().getApplicationContext()).width.intValue(), scalingInfo.width.intValue());
		assertEquals(DisplayUtility.getDisplaySize(getActivity().getApplicationContext()).height.intValue(), scalingInfo.height.intValue());
		assertNull(scalingInfo.sampleSize);
	}

	@UiThreadTest
	public void testOverrideSampleSize() {
		mImageLoader = new ImageLoader(getActivity());

		options.overrideSampleSize = 4;
		options.autoDetectBounds = true;
		options.useScreenSizeAsBounds = true;

		scalingInfo = mImageLoader.getScalingInfo(imageView, options);
		assertEquals(4, scalingInfo.sampleSize.intValue());
		assertNull(scalingInfo.width);
		assertNull(scalingInfo.height);
	}

	private void setParams(int width, int height) {
		LayoutParams params = new LayoutParams(width, height);
		imageView.setLayoutParams(params);
	}
}
