/*
 * Copyright 2012 Xtreme Labs
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.xtremelabs.imageutils.DiskDatabaseHelper.DiskDatabaseHelperObserver;

public class DiskLRUCacher implements ImageDiskCacherInterface {
	private static final int MAX_PERMANENT_STORAGE_IMAGE_DIMENSIONS_CACHED = 25; // TODO Optimize this value, or allow for API access to modify it.

	private long mMaximumCacheSizeInBytes = 30 * 1024 * 1024; // 30MB
	private final DiskManager mDiskManager;
	private final DiskDatabaseHelper mDatabaseHelper;
	private ImageDiskObserver mImageDiskObserver;
	private final CachedImagesMap mCachedImagesMap = new CachedImagesMap();
	private final MappedQueue<String, Dimensions> mPermanentStorageDimensionsCache = new MappedQueue<String, Dimensions>(MAX_PERMANENT_STORAGE_IMAGE_DIMENSIONS_CACHED);
	private final HashMap<DecodeOperationParameters, Runnable> mRequestToRunnableMap = new HashMap<DecodeOperationParameters, Runnable>();

	/*
	 * WARNING: Increasing the number of threads for image decoding will lag the UI thread.
	 * 
	 * It is highly recommended to leave the number of decode threads at one. Increasing this number too high will cause performance problems.
	 */
	private final LifoThreadPool mThreadPool = new LifoThreadPool(1);

	public DiskLRUCacher(Context appContext, ImageDiskObserver imageDecodeObserver) {
		mDiskManager = new DiskManager("img", appContext);
		mDatabaseHelper = new DiskDatabaseHelper(appContext, mDiskDatabaseHelperObserver);
		mImageDiskObserver = imageDecodeObserver;

		List<FileEntry> entries = mDatabaseHelper.getAllEntries();
		for (FileEntry entry : entries) {
			if (mDiskManager.isOnDisk(encode(entry.getUrl()))) {
				mCachedImagesMap.putDimensions(entry.getUrl(), entry.getDimensions());
			}
		}
	}

	@Override
	public boolean isCached(String uri) {
		boolean isPermanentStorageUri = isPermanentStorageUri(uri);

		if (isPermanentStorageUri) {
			return mPermanentStorageDimensionsCache.contains(uri);
		} else {
			return mCachedImagesMap.isCached(uri);
		}
	}

	private boolean isPermanentStorageUri(String uri) {
		boolean isPermanentStorageUri = false;
		try {
			URI imageUri = new URI(uri);
			String scheme = imageUri.getScheme();
			if (scheme != null && scheme.equals("file")) {
				isPermanentStorageUri = true;
			}
		} catch (URISyntaxException e) {
		}
		return isPermanentStorageUri;
	}

	@Override
	public int getSampleSize(String uri, Integer width, Integer height) {
		Dimensions dimensions = getImageDimensions(uri);
		if (dimensions == null) {
			return -1;
		}
		int sampleSize = calculateSampleSize(width, height, dimensions);
		return sampleSize;
	}

	@Override
	public void retrieveImageDetails(final String uri) {
		mThreadPool.execute(new Runnable() {
			@Override
			public void run() {
				cacheImageDetails(uri);
			}
		});
	}

	void cacheImageDetails(String uri) {
		try {
			URI imageUri = new URI(uri);
			String scheme = imageUri.getScheme();
			boolean isFileSystemUri = scheme == null ? false : scheme.equals("file");
			File file;

			if (isFileSystemUri) {
				file = new File(imageUri.getPath());
			} else {
				file = getFile(uri);
			}

			Dimensions dimensions = getImageDimensionsFromDisk(file);

			if (isFileSystemUri) {
				mPermanentStorageDimensionsCache.addOrBump(uri, dimensions);
			} else {
				mCachedImagesMap.putDimensions(uri, dimensions);
				mDatabaseHelper.addOrUpdateFile(uri, file.length(), dimensions.getWidth(), dimensions.getHeight());
				clearLeastUsedFilesInCache();
			}

			mImageDiskObserver.onImageDetailsRetrieved(uri);
		} catch (URISyntaxException e) {
			mImageDiskObserver.onImageDetailsRequestFailed(uri, "URISyntaxException caught when attempting to retrieve image details. URI: " + uri);
		} catch (FileNotFoundException e) {
			mImageDiskObserver.onImageDetailsRequestFailed(uri, "Image file not found. URI: " + uri);
		}
	}

	@Override
	public void getBitmapAsynchronouslyFromDisk(final String uri, final int sampleSize, final ImageReturnedFrom returnedFrom, final boolean noPreviousNetworkRequest) {
		final DecodeOperationParameters decodeOperationParameters = new DecodeOperationParameters(uri, sampleSize);

		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				boolean failed = false;
				String errorMessage = null;
				Bitmap bitmap = null;
				try {
					bitmap = getBitmapSynchronouslyFromDisk(uri, sampleSize);
				} catch (FileNotFoundException e) {
					failed = true;
					errorMessage = "Disk decode failed with error message: " + e.getMessage();
				} catch (FileFormatException e) {
					failed = true;
					errorMessage = "Disk decode failed with error message: " + e.getMessage();
				}
				removeRequestFromMap(decodeOperationParameters);

				if (!failed) {
					mImageDiskObserver.onImageDecoded(bitmap, uri, sampleSize, returnedFrom);
				} else {
					mDiskManager.deleteFile(encode(uri));
					mImageDiskObserver.onImageDecodeFailed(uri, sampleSize, errorMessage);
				}
			}
		};

		if (mapRunnableToParameters(runnable, decodeOperationParameters)) {
			mThreadPool.execute(runnable);
		}
	}

	@Override
	public void downloadImageFromInputStream(String uri, InputStream inputStream) throws IOException {
		mDiskManager.loadStreamToFile(inputStream, encode(uri));
		File file = getFile(uri);
		Dimensions dimensions = getImageDimensionsFromDisk(file);
		mCachedImagesMap.putDimensions(uri, dimensions);
		mDatabaseHelper.addOrUpdateFile(uri, file.length(), dimensions.getWidth(), dimensions.getHeight());
		clearLeastUsedFilesInCache();
	}

	@Override
	public void bumpOnDisk(String uri) {
		mDatabaseHelper.updateFile(uri);
	}

	// TODO This method should NOT be taking the sampleSize in directly, but rather the scaling info. The sampleSize should be calculated by the disk system.
	@Override
	public void bumpInQueue(String uri, int sampleSize) {
		DecodeOperationParameters parameters = new DecodeOperationParameters(uri, sampleSize);
		synchronized (mRequestToRunnableMap) {
			mThreadPool.bump(mRequestToRunnableMap.get(parameters));
		}
	}

	@Override
	public void setDiskCacheSize(long sizeInBytes) {
		mMaximumCacheSizeInBytes = sizeInBytes;
		clearLeastUsedFilesInCache();
	}

	@Override
	public Dimensions getImageDimensions(String uri) {
		boolean isFromPermanentStorage = isPermanentStorageUri(uri);

		Dimensions dimensions;
		if (isFromPermanentStorage) {
			dimensions = mPermanentStorageDimensionsCache.getValue(uri);
		} else {
			dimensions = mCachedImagesMap.getImageDimensions(uri);
		}

		return dimensions;
	}

	private boolean mapRunnableToParameters(Runnable runnable, DecodeOperationParameters parameters) {
		synchronized (mRequestToRunnableMap) {
			if (!mRequestToRunnableMap.containsKey(parameters)) {
				mRequestToRunnableMap.put(parameters, runnable);
				return true;
			} else {
				return false;
			}
		}
	}

	private void removeRequestFromMap(DecodeOperationParameters parameters) {
		synchronized (mRequestToRunnableMap) {
			mRequestToRunnableMap.remove(parameters);
		}
	}

	@Override
	public	Bitmap getBitmapSynchronouslyFromDisk(String uri, int sampleSize) throws FileNotFoundException, FileFormatException {
		File file = null;
		if (isPermanentStorageUri(uri)) {
			try {
				file = new File(new URI(uri).getPath());
			} catch (URISyntaxException e) {
				throw new FileNotFoundException("Bad URI.");
			}
		} else {
			file = getFile(uri);
		}
		FileInputStream fileInputStream = null;
		fileInputStream = new FileInputStream(file);
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = sampleSize;
		Bitmap bitmap = BitmapFactory.decodeStream(fileInputStream, null, opts);
		if (fileInputStream != null) {
			try {
				fileInputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (bitmap == null) {
			file.delete();
			throw new FileFormatException();
		}
		return bitmap;
	}

	/**
	 * Calculates a sample size that will potentially save memory and not result in a loss of quality when the image is made to fill the image view.
	 * 
	 * @param width
	 *            The image will not be scaled down to be smaller than this width. Null for no scaling by width.
	 * @param height
	 *            The image will not be scaled down to be smaller than this height. Null for no scaling by height.
	 * @param imageDimensions
	 *            The dimensions of the image, as decoded from the full image on disk.
	 * @return The calculated sample size. 1 if both height and width are null.
	 */
	public static int calculateSampleSize(Integer width, Integer height, Dimensions imageDimensions) {
		final int imageWidth = imageDimensions.getWidth();
		final int imageHeight = imageDimensions.getHeight();

		int sampleSize = 1;
		int widthSampleSize = -1;
		int heightSampleSize = -1;

		if (imageWidth > width && imageHeight > height) {
			if (width != null && imageWidth > width) {
				widthSampleSize = Math.round((float) imageWidth / (float) width);
			}

			if (height != null && imageHeight > height) {
				heightSampleSize = Math.round((float) imageHeight / (float) height);
			}

			if (widthSampleSize != -1 || heightSampleSize != -1) {
				if (widthSampleSize == -1) {
					sampleSize = heightSampleSize;
				} else if (heightSampleSize == -1) {
					sampleSize = widthSampleSize;
				} else {
					sampleSize = Math.min(widthSampleSize, heightSampleSize);
				}
			}
		}
		return sampleSize;
	}

	private synchronized void clearLeastUsedFilesInCache() {
		while (mDatabaseHelper.getTotalSizeOnDisk() > mMaximumCacheSizeInBytes) {
			String uri = mDatabaseHelper.getLRU().getUrl();
			mDiskManager.deleteFile(encode(uri));
			mDatabaseHelper.removeFile(uri);
			mCachedImagesMap.removeDimensions(uri);
		}
	}

	private File getFile(String uri) {
		return mDiskManager.getFile(encode(uri));
	}

	private String encode(String uri) {
		try {
			return URLEncoder.encode(uri, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}

	private Dimensions getImageDimensionsFromDisk(File file) throws FileNotFoundException {
		try {
			FileInputStream fileInputStream;
			fileInputStream = new FileInputStream(file);
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BitmapFactory.decodeStream(fileInputStream, null, o);
			return new Dimensions(o.outWidth, o.outHeight);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			throw e;
		}
	}

	public static class FileFormatException extends Exception {
		/**
		 * 
		 */
		private static final long serialVersionUID = -2180782787028503586L;
	}

	@Override
	public synchronized boolean isDecodeRequestPending(DecodeOperationParameters decodeOperationParameters) {
		return mRequestToRunnableMap.containsKey(decodeOperationParameters);
	}

	private final DiskDatabaseHelperObserver mDiskDatabaseHelperObserver = new DiskDatabaseHelperObserver() {
		@Override
		public void onDatabaseWiped() {
			mDiskManager.clearDirectory();
		}
	};

	void stubImageDiskObserver(ImageDiskObserver imageDecodeObserver) {
		mImageDiskObserver = imageDecodeObserver;
	}
}
