package com.xtremelabs.imageutils;

import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import com.xtremelabs.imageutils.AsyncOperationsMaps.AsyncOperationState;
import com.xtremelabs.imageutils.DiskLRUCacher.FileFormatException;

/**
 * This class defensively handles requests from four locations: LifecycleReferenceManager, ImageMemoryCacherInterface, ImageDiskCacherInterface, ImageNetworkInterface and the AsyncOperationsMaps.
 * 
 * The job of this class is to "route" messages appropriately in order to ensure synchronized handling of image downloading and caching operations.
 */
public class ImageCacher implements ImageDownloadObserver, ImageDiskObserver, AsyncOperationsObserver {
	private static final String PREFIX = "IMAGE CACHER - ";

	private static final String FILE_SYSTEM_SCHEME = "file";

	private static ImageCacher mImageCacher;

	private ImageDiskCacherInterface mDiskCache;
	private ImageMemoryCacherInterface mMemoryCache;
	private ImageNetworkInterface mNetworkInterface;

	private AsyncOperationsMaps mAsyncOperationsMap;

	private ImageCacher(Context appContext) {
		if (Build.VERSION.SDK_INT <= 11) {
			mMemoryCache = new SizeEstimatingMemoryLRUCacher();
		} else {
			mMemoryCache = new AdvancedMemoryLRUCacher();
		}

		mDiskCache = new DiskLRUCacher(appContext, this);
		mNetworkInterface = new ImageDownloader(mDiskCache, this);
		mAsyncOperationsMap = new AsyncOperationsMaps(this);
	}

	public static synchronized ImageCacher getInstance(Context appContext) {
		if (mImageCacher == null) {
			mImageCacher = new ImageCacher(appContext);
		}
		return mImageCacher;
	}
	
	public Bitmap getBitmapForWidget(String url, ImageCacherListener cacherListener, ScalingInfo scalingInfo) {
	     int sampleSize = getSampleSize(url, scalingInfo);
	     Bitmap bitmap;

	     if (mDiskCache.isCached(url) && sampleSize != -1) {
	          if ((bitmap = mMemoryCache.getBitmap(url, sampleSize)) != null) {
	               return bitmap;
	          } else {
	               try {
	                    return mDiskCache.getBitmapSynchronouslyFromDisk(url, sampleSize);
	               } catch (FileNotFoundException e) {
	                    // TODO Auto-generated catch block
	                    e.printStackTrace();
	               } catch (FileFormatException e) {
	                   // TODO Auto-generated catch block
	                   e.printStackTrace();
	              }
	          }
	     } else {
	          downloadImageFromNetwork(url, cacherListener, scalingInfo);
	     }

	     return null;
	}

	public Bitmap getBitmap(String uri, ImageCacherListener imageCacherListener, ScalingInfo scalingInfo) {
		throwExceptionIfNeeded(uri, imageCacherListener, scalingInfo);

		AsyncOperationState asyncOperationState = mAsyncOperationsMap.queueListenerIfRequestPending(imageCacherListener, uri, scalingInfo);

		switch (asyncOperationState) {
		case QUEUED_FOR_NETWORK_REQUEST:
			mNetworkInterface.bump(uri);
			return null;
		case QUEUED_FOR_DECODE_REQUEST:
			mDiskCache.bumpInQueue(uri, getSampleSize(uri, scalingInfo));
			return null;
		case QUEUED_FOR_DETAILS_REQUEST:
			mDiskCache.bumpInQueue(uri, 0);
			return null;
		}

		int sampleSize = getSampleSize(uri, scalingInfo);

		// TODO: Look into removing the sampleSize check.

		if (mDiskCache.isCached(uri) && sampleSize != -1) {
			Bitmap bitmap;
			if ((bitmap = mMemoryCache.getBitmap(uri, sampleSize)) != null) {
				return bitmap;
			} else {
				decodeBitmapFromDisk(uri, imageCacherListener, sampleSize);
			}
		} else if (checkIsFileSystemURI(uri)) {
			retrieveImageDetails(uri, imageCacherListener, scalingInfo);
		} else {
			downloadImageFromNetwork(uri, imageCacherListener, scalingInfo);
		}

		return null;
	}

	@Override
	public int getSampleSize(String url, ScalingInfo scalingInfo) {
		int sampleSize;
		if (scalingInfo.sampleSize != null) {
			sampleSize = scalingInfo.sampleSize;
		} else {
			sampleSize = mDiskCache.getSampleSize(url, scalingInfo.width, scalingInfo.height);
		}
		return sampleSize;
	}

	/**
	 * Caches the image at the provided url to disk. If the image is already on disk, it gets bumped on the eviction queue.
	 * 
	 * @param url
	 */
	public synchronized void precacheImage(String url) {
		validateUri(url);

		if (!mAsyncOperationsMap.isNetworkRequestPendingForUrl(url) && !mDiskCache.isCached(url)) {
			mNetworkInterface.downloadImageToDisk(url);
		} else {
			mDiskCache.bumpOnDisk(url);
		}
	}

	public void clearMemCache() {
		mMemoryCache.clearCache();
	}

	public void setMaximumCacheSize(long size) {
		mMemoryCache.setMaximumCacheSize(size);
	}

	public void cancelRequestForBitmap(ImageCacherListener imageCacherListener) {
		if (Logger.logAll()) {
			Logger.d(PREFIX + "Cancelling a request.");
		}
		mAsyncOperationsMap.cancelPendingRequest(imageCacherListener);
	}

	private void downloadImageFromNetwork(String uri, ImageCacherListener imageCacherListener, ScalingInfo scalingInfo) {
		mAsyncOperationsMap.registerListenerForNetworkRequest(imageCacherListener, uri, scalingInfo);
		mNetworkInterface.downloadImageToDisk(uri);
	}

	private void retrieveImageDetails(String uri, ImageCacherListener imageCacherListener, ScalingInfo scalingInfo) {
		mAsyncOperationsMap.registerListenerForDetailsRequest(imageCacherListener, uri, scalingInfo);
		mDiskCache.retrieveImageDetails(uri);
	}

	private void decodeBitmapFromDisk(String uri, ImageCacherListener imageCacherListener, int sampleSize) {
		mAsyncOperationsMap.registerListenerForDecode(imageCacherListener, uri, sampleSize);
		mDiskCache.getBitmapAsynchronouslyFromDisk(uri, sampleSize, ImageReturnedFrom.DISK, true);
	}

	private void validateUri(String uri) {
		if (uri == null || uri.length() == 0)
			throw new IllegalArgumentException("Null URI passed into the image system.");
	}

	private void throwExceptionIfNeeded(String uri, ImageCacherListener imageCacherListener, ScalingInfo scalingInfo) {
		ThreadChecker.throwErrorIfOffUiThread();

		validateUri(uri);

		if (imageCacherListener == null) {
			throw new IllegalArgumentException("The ImageCacherListener must not be null.");
		}

		if (scalingInfo == null) {
			throw new IllegalArgumentException("The ScalingInfo must not be null.");
		}
	}

	private static boolean checkIsFileSystemURI(String uri) {
		try {
			URI u = new URI(uri);
			String scheme = u.getScheme();
			if (scheme == null) {
				return false;
			}
			return scheme.equals(FILE_SYSTEM_SCHEME);
		} catch (URISyntaxException e) {
			return false;
		}
	}

	public static abstract class ImageCacherListener {
		public abstract void onImageAvailable(Bitmap bitmap, ImageReturnedFrom returnedFrom);

		public abstract void onFailure(String message);
	}

	@Override
	public void onImageDecoded(Bitmap bitmap, String uri, int sampleSize, ImageReturnedFrom returnedFrom) {
		mMemoryCache.cacheBitmap(bitmap, uri, sampleSize);
		mAsyncOperationsMap.onDecodeSuccess(bitmap, uri, sampleSize, returnedFrom);
	}

	@Override
	public void onImageDecodeFailed(String uri, int sampleSize, String message) {
		mAsyncOperationsMap.onDecodeFailed(uri, sampleSize, message);
	}

	@Override
	public void onImageDownloaded(String uri) {
		mAsyncOperationsMap.onDownloadComplete(uri);
	}

	@Override
	public void onImageDownloadFailed(String uri, String message) {
		mAsyncOperationsMap.onDownloadFailed(uri, message);
	}

	@Override
	public void onImageDecodeRequired(String uri, int sampleSize) {
		mDiskCache.getBitmapAsynchronouslyFromDisk(uri, sampleSize, ImageReturnedFrom.NETWORK, false);
	}

	@Override
	public boolean isNetworkRequestPendingForUrl(String url) {
		return mNetworkInterface.isNetworkRequestPendingForUrl(url);
	}

	@Override
	public boolean isDecodeRequestPending(DecodeOperationParameters decodeOperationParameters) {
		return mDiskCache.isDecodeRequestPending(decodeOperationParameters);
	}

	void stubMemCache(ImageMemoryCacherInterface imageMemoryCacherInterface) {
		mMemoryCache = imageMemoryCacherInterface;
	}

	void stubDiskCache(ImageDiskCacherInterface imageDiskCacherInterface) {
		mDiskCache = imageDiskCacherInterface;
	}

	void stubNetwork(ImageNetworkInterface imageNetworkInterface) {
		mNetworkInterface = imageNetworkInterface;
	}

	void stubAsynchOperationsMaps(AsyncOperationsMaps asyncOperationsMaps) {
		mAsyncOperationsMap = asyncOperationsMaps;
	}

	@Override
	public void onImageDetailsRetrieved(String uri) {
		mAsyncOperationsMap.onDetailsRequestComplete(uri);
	}

	@Override
	public void onImageDetailsRequestFailed(String uri, String message) {
		mAsyncOperationsMap.onDetailsRequestFailed(uri, message);
	}

	@Override
	public void onImageDetailsRequired(String uri) {
		mDiskCache.retrieveImageDetails(uri);
	}
}
