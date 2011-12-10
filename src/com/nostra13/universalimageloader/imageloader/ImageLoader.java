package com.nostra13.universalimageloader.imageloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

import com.nostra13.universalimageloader.Constants;
import com.nostra13.universalimageloader.cache.Cache;
import com.nostra13.universalimageloader.cache.ImageCache;
import com.nostra13.universalimageloader.utils.FileUtils;
import com.nostra13.universalimageloader.utils.StorageUtils;

/**
 * Singltone for image loading and displaying at {@link ImageView ImageViews}
 * 
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 */
public final class ImageLoader {

	public static final String TAG = ImageLoader.class.getSimpleName();

	private final Cache<String, Bitmap> bitmapCache = new ImageCache(Constants.MEMORY_CACHE_SIZE);
	private final File cacheDir;

	private final List<PhotoToLoad> photoToLoadQueue = new LinkedList<ImageLoader.PhotoToLoad>();
	private final PhotosLoader photoLoaderThread = new PhotosLoader();
	private final DisplayImageOptions defaultOptions = DisplayImageOptions.createSimple();

	private volatile static ImageLoader instance = null;

	/** Returns singletone class instance */
	public static ImageLoader getInstance(Context context) {
		if (instance == null) {
			synchronized (ImageLoader.class) {
				if (instance == null) {
					instance = new ImageLoader(context);
				}
			}
		}
		return instance;
	}

	private ImageLoader(Context context) {
		// Make the background thread low priority. This way it will not affect the UI performance
		photoLoaderThread.setPriority(Thread.NORM_PRIORITY - 1);
		// Find the directory to save cached images
		cacheDir = StorageUtils.getCacheDirectory(context);
	}

	/**
	 * Adds display image task to queue. Image will be set to ImageView when it's turn. <br/>
	 * {@linkplain DisplayImageOptions Display image options} {@linkplain DisplayImageOptions#createForListView()
	 * appropriated for ListViews will be used}.
	 * 
	 * @param url
	 *            Image URL (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView
	 *            {@link ImageView} which should display image
	 */
	public void displayImage(String url, ImageView imageView) {
		displayImage(url, imageView, defaultOptions, null);
	}

	/**
	 * Add display image task to queue. Image will be set to ImageView when it's turn.
	 * 
	 * @param url
	 *            Image URL (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView
	 *            {@link ImageView} which should display image
	 * @param options
	 *            {@link DisplayImageOptions Display options} for image displaying
	 */
	public void displayImage(String url, ImageView imageView, DisplayImageOptions options) {
		displayImage(url, imageView, options, null);
	}

	/**
	 * Add display image task to queue. Image will be set to ImageView when it's turn.
	 * 
	 * @param url
	 *            Image URL (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView
	 *            {@link ImageView} which should display image
	 * @param options
	 *            {@link DisplayImageOptions Display options} for image displaying
	 * @param listener
	 *            {@link ImageLoadingListener Listener} for image loading process. Listener fires events only if there
	 *            is no image for loading in memory cache. If there is image for loading in memory cache then image is
	 *            displayed at ImageView but listener does not fire any event. Listener fires events on UI thread.
	 */
	public void displayImage(String url, ImageView imageView, DisplayImageOptions options, ImageLoadingListener listener) {
		if (url == null || url.length() == 0) {
			return;
		}
		imageView.setTag(Constants.IMAGE_LOADER_TAG_KEY, url);

		PhotoToLoad photoToLoad = new PhotoToLoad(url, imageView, options, listener);

		Bitmap image = null;
		synchronized (bitmapCache) {
			image = bitmapCache.get(url);
		}

		if (image != null && !image.isRecycled()) {
			imageView.setImageBitmap(image);
		} else {
			queuePhoto(photoToLoad);
			if (options.isShowStubImage()) {
				imageView.setImageResource(options.getStubImage());
			} else {
				imageView.setImageBitmap(null);
			}
		}
	}

	private void queuePhoto(PhotoToLoad photoToLoad) {
		if (photoToLoad.listener != null) {
			photoToLoad.listener.onLoadingStarted();
		}

		// This ImageView may be used for other images before. So there may be some old tasks in the queue. We need to discard them.
		synchronized (photoToLoadQueue) {
			removeFromQueue(photoToLoad.imageView);
		}

		// If image was cached on disc we put load image task in front of the queue. 
		// If not - we put load image task in the end of the queue.
		// Images are loaded from the queue beginning. So it will reduce the time of waiting 
		// to display cached images (they will be displayed first)
		boolean isCachedOnDisc = isCachedOnDisc(photoToLoad.url);
		synchronized (photoToLoadQueue) {
			if (isCachedOnDisc) {
				photoToLoadQueue.add(0, photoToLoad);
			} else {
				photoToLoadQueue.add(photoToLoad);
			}
			photoToLoadQueue.notifyAll();
		}

		// Start thread if it's not started yet
		if (photoLoaderThread.getState() == Thread.State.NEW) {
			photoLoaderThread.start();
		}
	}

	private boolean isCachedOnDisc(String url) {
		boolean result = false;
		File f = getLocalImageFile(url);

		try {
			result = f.exists();
		} catch (Exception e) {
		}

		return result;
	}

	private File getLocalImageFile(String imageUrl) {
		String fileName = String.valueOf(imageUrl.hashCode());
		return new File(cacheDir, fileName);
	}

	public void removeFromQueue(ImageView image) {
		Iterator<PhotoToLoad> it = photoToLoadQueue.iterator();
		while (it.hasNext()) {
			PhotoToLoad photo = it.next();
			if (photo.imageView == image) {
				it.remove();
			}
		}
	}

	private Bitmap getBitmap(String imageUrl, ImageSize targetImageSize, boolean cacheImageOnDisc) {
		File f = getLocalImageFile(imageUrl);

		// Try to load image from disc cache
		try {
			if (f.exists()) {
				Bitmap b = ImageDecoder.decodeFile(f.toURL(), targetImageSize);
				if (b != null) {
					return b;
				}
			}
		} catch (IOException e) {
			// There is no image in disc cache. Do nothing
		}

		// Load image from Web
		Bitmap bitmap = null;
		try {
			URL imageUrlForDecoding = null;
			if (cacheImageOnDisc) {
				saveImageFromUrl(imageUrl, f);
				imageUrlForDecoding = f.toURL();
			} else {
				imageUrlForDecoding = new URL(imageUrl);
			}

			bitmap = ImageDecoder.decodeFile(imageUrlForDecoding, targetImageSize);
		} catch (Exception ex) {
			Log.e(TAG, String.format("Exception while loading bitmap from URL=%s : %s", imageUrl, ex.getMessage()), ex);
			if (f.exists()) {
				f.delete();
			}
		}
		return bitmap;
	}

	private void saveImageFromUrl(String imageUrl, File targetFile) throws MalformedURLException, IOException {
		HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
		conn.setConnectTimeout(Constants.HTTP_CONNECT_TIMEOUT);
		conn.setReadTimeout(Constants.HTTP_READ_TIMEOUT);
		InputStream is = conn.getInputStream();
		try {
			OutputStream os = new FileOutputStream(targetFile);
			try {
				FileUtils.copyStream(is, os);
			} finally {
				os.close();
			}
		} finally {
			is.close();
		}
	}

	public void stopThread() {
		photoLoaderThread.interrupt();
	}

	/**
	 * Compute image size for loading at memory (for memory economy).<br />
	 * Size computing algorithm:<br />
	 * 1) Gets maxWidth and maxHeight. If both of them are not set then go to step #2. (<i>this step is not working
	 * now</i>)</br > 2) Get layout_width and layout_height. If both of them are not set then go to step #3.</br > 3)
	 * Get device screen dimensions.
	 */
	private ImageSize getImageSizeScaleTo(ImageView imageView) {
		int width = -1;
		int height = -1;

		// Check maxWidth and maxHeight parameters
		try {
			Field maxWidthField = ImageView.class.getDeclaredField("mMaxWidth");
			Field maxHeightField = ImageView.class.getDeclaredField("mMaxHeight");
			maxWidthField.setAccessible(true);
			maxHeightField.setAccessible(true);
			int maxWidth = (Integer) maxWidthField.get(imageView);
			int maxHeight = (Integer) maxHeightField.get(imageView);

			if (maxWidth >= 0 && maxWidth < Integer.MAX_VALUE) {
				width = maxWidth;
			}
			if (maxHeight >= 0 && maxHeight < Integer.MAX_VALUE) {
				height = maxHeight;
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage(), e);
		}

		if (width < 0 && height < 0) {
			// Get layout width and height parameters
			LayoutParams params = imageView.getLayoutParams();
			width = params.width;
			height = params.height;
		}
		return new ImageSize(width, height);
	}

	public void clearMemoryCache() {
		synchronized (bitmapCache) {
			bitmapCache.clear();
		}
	}

	public void clearDiscCache() {
		File[] files = cacheDir.listFiles();
		for (File f : files)
			f.delete();
	}

	// Task for the queue
	private class PhotoToLoad {
		private String url;
		private ImageView imageView;
		private DisplayImageOptions options;
		private ImageLoadingListener listener;

		public PhotoToLoad(String url, ImageView imageView, DisplayImageOptions options, ImageLoadingListener listener) {
			this.url = url;
			this.imageView = imageView;
			this.options = options;
			this.listener = listener;
		}
	}

	class PhotosLoader extends Thread {
		@Override
		public void run() {
			while (true) {
				PhotoToLoad photoToLoad = null;
				Bitmap bmp = null;
				try {
					// thread waits until there are any images to load in the queue
					if (photoToLoadQueue.isEmpty()) {
						synchronized (photoToLoadQueue) {
							photoToLoadQueue.wait();
						}
					}
					if (!photoToLoadQueue.isEmpty()) {
						synchronized (photoToLoadQueue) {
							photoToLoad = photoToLoadQueue.remove(0);
						}
					}

					if (photoToLoad != null) {
						ImageSize targetImageSize = getImageSizeScaleTo(photoToLoad.imageView);
						bmp = getBitmap(photoToLoad.url, targetImageSize, photoToLoad.options.isCacheOnDisc());
						if (bmp == null) {
							continue;
						}
						if (photoToLoad.options.isCacheInMemory()) {
							synchronized (bitmapCache) {
								bitmapCache.put(photoToLoad.url, bmp);
							}
						}
					}

					if (Thread.interrupted()) {
						break;
					}
				} catch (InterruptedException e) {
					Log.e(TAG, e.getMessage(), e);
				} finally {
					if (photoToLoad != null) {
						BitmapDisplayer bd = new BitmapDisplayer(photoToLoad, bmp);
						Activity a = (Activity) photoToLoad.imageView.getContext();
						a.runOnUiThread(bd);
					}
				}
			}
		}
	}

	/** Used to display bitmap in the UI thread */
	class BitmapDisplayer implements Runnable {
		Bitmap bitmap;
		PhotoToLoad photoToLoad;

		public BitmapDisplayer(PhotoToLoad photoToLoad, Bitmap bitmap) {
			this.bitmap = bitmap;
			this.photoToLoad = photoToLoad;
		}

		public void run() {
			String tag = (String) photoToLoad.imageView.getTag(Constants.IMAGE_LOADER_TAG_KEY);

			if (photoToLoad != null && tag != null && tag.equals(photoToLoad.url) && bitmap != null) {
				photoToLoad.imageView.setImageBitmap(bitmap);

				if (photoToLoad.listener != null) {
					photoToLoad.listener.onLoadingComplete();
				}
			}
		}
	}

	class ImageSize {
		int width;
		int height;

		public ImageSize(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}
}