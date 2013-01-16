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

import java.util.List;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.widget.BaseAdapter;

import com.xtremelabs.imageutils.AbstractImageLoader.Options;

/**
 * This utility simplifies the process of implementing precaching in adapters for use in widgets such as ListViews and ViewPagers.<br>
 * <br>
 * <b>Instructions:</b><br>
 * Create a new instance of this class from within adapter.<br>
 * Implement the methods {@link PrecacheInformationProvider#onRowPrecacheRequestsRequired(int)} and {@link PrecacheInformationProvider#getCount()}.<br>
 * In the "getView" method of the adapter, call {@link #onPositionVisited(int)}, and pass in the current position.
 */
/*
 * TODO This class still has a couple of inefficiencies. Namely, there are some duplicate calls being made. Find the duplicate calls and code them away.
 * 
 * TODO This class' API needs some additional work. The PrecacheInformationProvider needs to be using options and/or accept an ImageView so its API is closer to that of the ImageLoader.loadImage
 * methods.
 * 
 * TODO This class needs the ability to support adapters that loop from position 0 -> count - 1, and count-1 -> 0.
 */
public class ImagePrecacheAssistant {
	private enum Direction {
		DOWN, UP
	}

	private static final int DEFAULT_MEM_CACHE_RANGE = 4;
	private static final int DEFAULT_DISK_CACHE_RANGE = 10;

	private int mMemCacheRange = DEFAULT_MEM_CACHE_RANGE;
	private int mDiskCacheRange = DEFAULT_DISK_CACHE_RANGE;

	private final AbstractImageLoader mImageLoader;
	private final PrecacheInformationProvider mPrecacheInformationProvider;

	private int mCurrentPosition = 0;
	private Direction mCurrentDirection = Direction.UP;
	private boolean isFirstCalculation = true;

	public ImagePrecacheAssistant(AbstractImageLoader imageLoader, PrecacheInformationProvider precacheInformationProvider) {
		mImageLoader = imageLoader;
		mPrecacheInformationProvider = precacheInformationProvider;
	}

	/**
	 * This method must be called in the getView method of your adapter.
	 * 
	 * @param position
	 *            The current position within the adapter.
	 */
	public void onPositionVisited(int position) {
		calculateDirection(position);
		RangesToCache ranges = calculateRanges(position);

		for (int i = ranges.diskCacheLowerIndex; i < ranges.diskCacheUpperIndex; i++) {
			List<String> precacheRequestUris = mPrecacheInformationProvider.onRowPrecacheRequestsForDiskCacheRequired(i);
			precacheListToDisk(precacheRequestUris);
		}

		for (int i = ranges.memCacheLowerIndex; i < ranges.memCacheUpperIndex; i++) {
			List<PrecacheRequest> precacheRequests = mPrecacheInformationProvider.onRowPrecacheRequestsForMemoryCacheRequired(i);
			precacheListToMemory(precacheRequests);
		}
	}

	/**
	 * Adjust the number of positions ahead that become cached in both the disk and memory caches.
	 * 
	 * @param range
	 */
	public void setMemCacheRange(int range) {
		mMemCacheRange = range;
	}

	/**
	 * Adjust the number of positions ahead of those that become cached in memory that will be cached on disk.
	 * 
	 * @param range
	 */
	public void setDiskCacheRange(int range) {
		mDiskCacheRange = range;
	}

	private void precacheListToMemory(List<PrecacheRequest> precacheRequests) {
		if (precacheRequests != null) {
			for (PrecacheRequest precacheRequest : precacheRequests) {
				mImageLoader.precacheImageToDiskAndMemory(precacheRequest.mUri, precacheRequest.mBounds, precacheRequest.mOptions);
			}
		}
	}

	private void precacheListToDisk(List<String> precacheRequestUris) {
		if (precacheRequestUris != null) {
			for (String precacheRequestUri : precacheRequestUris) {
				mImageLoader.precacheImageToDisk(precacheRequestUri);
			}
		}
	}

	private RangesToCache calculateRanges(int position) {
		RangesToCache indices = new RangesToCache();

		switch (mCurrentDirection) {
		case UP:
			calculateRangesForUp(position, indices);
			break;
		case DOWN:
			calculateRangesForDown(position, indices);
			break;
		}

		return indices;
	}

	private void calculateDirection(int position) {
		if (directionSwitchedToDown(position)) {
			mCurrentDirection = Direction.DOWN;
		} else if (directionSwitchedToUp(position)) {
			mCurrentDirection = Direction.UP;
		}

		switch (mCurrentDirection) {
		case DOWN:
			mCurrentPosition = position;
			break;
		case UP:
			mCurrentPosition = position;
			break;
		}
	}

	private void calculateRangesForUp(int position, RangesToCache indices) {
		if (isFirstCalculation) {
			indices.memCacheUpperIndex = Math.max(0, position);
		} else {
			indices.memCacheUpperIndex = Math.max(0, position - mMemCacheRange + 1);
		}
		indices.memCacheLowerIndex = Math.max(0, position - mMemCacheRange);

		if (isFirstCalculation) {
			indices.diskCacheUpperIndex = Math.max(0, indices.memCacheLowerIndex);
		} else {
			indices.diskCacheUpperIndex = Math.max(0, indices.memCacheLowerIndex - mDiskCacheRange + 1);
		}
		indices.diskCacheLowerIndex = Math.max(0, indices.memCacheLowerIndex - mDiskCacheRange);

		isFirstCalculation = false;
	}

	private void calculateRangesForDown(int position, RangesToCache indices) {
		int count = mPrecacheInformationProvider.getCount();

		if (isFirstCalculation) {
			indices.memCacheLowerIndex = Math.min(count, position + 1);
		} else {
			indices.memCacheLowerIndex = Math.min(count, position + mMemCacheRange);
		}
		indices.memCacheUpperIndex = Math.min(count, position + 1 + mMemCacheRange);

		if (isFirstCalculation) {
			indices.diskCacheLowerIndex = Math.min(count, indices.memCacheUpperIndex);
		} else {
			indices.diskCacheLowerIndex = Math.min(count, indices.memCacheUpperIndex + mDiskCacheRange - 1);
		}
		indices.diskCacheUpperIndex = Math.min(count, indices.memCacheUpperIndex + mDiskCacheRange);

		isFirstCalculation = false;
	}

	private boolean directionSwitchedToDown(int position) {
		return mCurrentDirection == Direction.UP && position >= mCurrentPosition;
	}

	private boolean directionSwitchedToUp(int position) {
		return mCurrentDirection == Direction.DOWN && position <= mCurrentPosition;
	}

	/**
	 * This interface must be implemented in order for the {@link ImagePrecacheAssistant} to function.
	 */
	public static interface PrecacheInformationProvider {
		/**
		 * This method must return the number of elements in the adapter. In most cases, it should return the same value as the {@link BaseAdapter#getCount()} method.
		 * 
		 * @return
		 */
		public int getCount();

		/**
		 * This method returns a list of the URIs that are required for a particular position in the adapter.<br>
		 * <br>
		 * If there are no image URIs available for the provided position, an empty list should be returned.
		 * 
		 * @param position
		 *            The position for which images will be precached.
		 * @return A list of {@link PrecacheRequest}s. Each PrecacheRequest should contain a URI for a particular image and the bounds of the view the image will be loaded into. The bounds should be
		 *         provided in pixels, or be given as null.
		 */
		// public List<PrecacheRequest> onRowPrecacheRequestsRequired(int position);

		public List<String> onRowPrecacheRequestsForDiskCacheRequired(int position);

		public List<PrecacheRequest> onRowPrecacheRequestsForMemoryCacheRequired(int position);
	}

	public static class PrecacheRequest {
		public static enum UnitType {
			PIXELS, DENSITY_INDEPENDENT_PIXELS
		}

		private String mUri;
		private Dimensions mBounds;
		private Options mOptions;

		/**
		 * @param uri
		 * @param bounds
		 *            The dimensions of the image view the URI will be loaded into. If one or more dimensions are unknown, simply specify the dimensions as null.
		 */
		public static PrecacheRequest generatePrecacheRequest(Context context, String uri, Dimensions bounds, UnitType unitType) {
			PrecacheRequest request = new PrecacheRequest();

			request.mUri = uri;

			Dimensions dimensions = null;
			switch (unitType) {
			case DENSITY_INDEPENDENT_PIXELS:
				dimensions = new Dimensions(convertToPixels(context, bounds.width), convertToPixels(context, bounds.height));
				break;
			case PIXELS:
				dimensions = new Dimensions(bounds);
				break;
			}
			request.mBounds = dimensions;

			return request;
		}

		public void setOptions(Options options) {
			mOptions = options;
		}

		private static Integer convertToPixels(Context context, Integer sizeInDp) {
			if (sizeInDp == null) {
				return null;
			}

			Resources resources = context.getResources();
			return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeInDp, resources.getDisplayMetrics());
		}
	}

	private static class RangesToCache {
		int memCacheLowerIndex = 0, memCacheUpperIndex = 0;
		int diskCacheLowerIndex = 0, diskCacheUpperIndex = 0;
	}
}
