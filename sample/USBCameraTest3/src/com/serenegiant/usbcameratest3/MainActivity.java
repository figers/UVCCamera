package com.serenegiant.usbcameratest3;
/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 * 
 * Copyright (c) 2014 saki t_saki@serenegiant.com
 * 
 * File name: MainActivity.java
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * 
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb and jin/libuvc folder may have a different license, see the respective files.
*/

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.encoder.MediaAudioEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaMuxerWrapper;
import com.serenegiant.encoder.MediaSurfaceEncoder;
import com.serenegiant.encoder.MediaVideoEncoder;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.CameraViewInterface;

public class MainActivity extends Activity {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "MainActivity";

	/**
	 * set true if you want to record movie using MediaSurfaceEncoder
	 * (writing frame data into Surface camera from MediaCodec
	 *  by almost same way as USBCameratest2)
	 * set false if you want to record movie using MediaVideoEncoder
	 */
    private static final boolean USE_SURFACE_ENCODER = true;
	/**
	 * for accessing USB
	 */
	private USBMonitor mUSBMonitor;
	/**
	 * Handler to execute camera releated methods sequentially on private thread
	 */
	private CameraHandler mHandler;
	/**
	 * for camera preview display
	 */
	private CameraViewInterface mUVCCameraView;
	/**
	 * for open&start / stop&close camera preview
	 */
	private ToggleButton mCameraButton;
	/**
	 * button for start/stop recording
	 */
	private ImageButton mCaptureButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (DEBUG) Log.v(TAG, "onCreate:");
		if (USE_SURFACE_ENCODER)
			setContentView(R.layout.activity_main2);
		else
			setContentView(R.layout.activity_main);
		mCameraButton = (ToggleButton)findViewById(R.id.camera_button);
		mCameraButton.setOnClickListener(mOnClickListener);
		mCaptureButton = (ImageButton)findViewById(R.id.capture_button);
		mCaptureButton.setOnClickListener(mOnClickListener);
		mCaptureButton.setVisibility(View.INVISIBLE);
		final View view = findViewById(R.id.camera_view);
		view.setOnLongClickListener(mOnLongClickListener);
		mUVCCameraView = (CameraViewInterface)view;
		mUVCCameraView.setAspectRatio(640 / 480.f);

		mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
		mHandler = CameraHandler.createHandler(this, mUVCCameraView);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (DEBUG) Log.v(TAG, "onResume:");
		mUSBMonitor.register();
		if (mUVCCameraView != null)
			mUVCCameraView.onResume();
	}

	@Override
	public void onPause() {
		if (DEBUG) Log.v(TAG, "onPause:");
//		mHandler.stopRecording();
//		mHandler.stopPreview();
    	mHandler.closeCamera();
		if (mUVCCameraView != null)
			mUVCCameraView.onPause();
		mCameraButton.setChecked(false);
		mCaptureButton.setVisibility(View.INVISIBLE);
		mUSBMonitor.unregister();
		super.onPause();
	}

	@Override
	public void onDestroy() {
		if (DEBUG) Log.v(TAG, "onDestroy:");
        if (mHandler != null) {
//	        mHandler.release();
	        mHandler = null;
        }
        if (mUSBMonitor != null) {
	        mUSBMonitor.destroy();
	        mUSBMonitor = null;
        }
        mUVCCameraView = null;
        mCameraButton = null;
        mCaptureButton = null;
		super.onDestroy();
	}

	/**
	 * event handler when click camera / capture button
	 */
	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(View view) {
			switch (view.getId()) {
			case R.id.camera_button:
				if (!mHandler.isCameraOpened()) {
					CameraDialog.showDialog(MainActivity.this);
				} else {
					mHandler.closeCamera();
					mCaptureButton.setVisibility(View.INVISIBLE);
				}
				break;
			case R.id.capture_button:
				if (mHandler.isCameraOpened()) {
					if (!mHandler.isRecording()) { 
						mCaptureButton.setColorFilter(0xffff0000);	// turn red
						mHandler.startRecording();
					} else {
						mCaptureButton.setColorFilter(0);	// return to default color
						mHandler.stopRecording();
					}
				}
				break;
			}
		}
	};

	/**
	 * capture still image when you long click on preview image(not on buttons) 
	 */
	private final OnLongClickListener mOnLongClickListener = new OnLongClickListener() {
		@Override
		public boolean onLongClick(View view) {
			switch (view.getId()) {
			case R.id.camera_view:
				mHandler.captureStill();
				return true;
			}
			return false;
		}
	};

	private void startPreview() {
		final SurfaceTexture st = mUVCCameraView.getSurfaceTexture(); 
		mHandler.startPreview(new Surface(st));
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mCaptureButton.setVisibility(View.VISIBLE);
			}
		});
	}

	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(UsbDevice device) {
			Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onConnect(UsbDevice device, final UsbControlBlock ctrlBlock, boolean createNew) {
			if (DEBUG) Log.v(TAG, "onConnect:");
			mHandler.openCamera(ctrlBlock);
			startPreview();
		}

		@Override
		public void onDisconnect(UsbDevice device, UsbControlBlock ctrlBlock) {
			if (DEBUG) Log.v(TAG, "onDisconnect:");
			if (mHandler != null) {
				mHandler.closeCamera();
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						mCaptureButton.setVisibility(View.INVISIBLE);
						mCameraButton.setChecked(false);
					}
				});
			}
		}
		@Override
		public void onDettach(UsbDevice device) {
			Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
		}
	};

	/**
	 * to access from CameraDialog
	 * @return
	 */
	public USBMonitor getUSBMonitor() {
		return mUSBMonitor;
	}

	/**
	 * Handler class to execute camera releated methods sequentially on private thread 
	 */
	private static final class CameraHandler extends Handler {
		private static final int MSG_OPEN = 0;
		private static final int MSG_CLOSE = 1;
		private static final int MSG_PREVIEW_START = 2;
		private static final int MSG_PREVIEW_STOP = 3;
		private static final int MSG_CAPTURE_STILL = 4;
		private static final int MSG_CAPTURE_START = 5;
		private static final int MSG_CAPTURE_STOP = 6;
		private static final int MSG_MEDIA_UPDATE = 7;
		private static final int MSG_RELEASE = 9;

		private final WeakReference<CameraThread> mWeakThread;

		public static final CameraHandler createHandler(MainActivity parent, CameraViewInterface cameraView) {
			CameraThread thread = new CameraThread(parent, cameraView);
			thread.start();
			return thread.getHandler(); 
		}

		private CameraHandler(CameraThread thread) {
			mWeakThread = new WeakReference<CameraThread>(thread);
		}

		public boolean isCameraOpened() {
			final CameraThread thread = mWeakThread.get();
			return thread != null ? thread.isCameraOpened() : false;
		}

		public boolean isRecording() {
			final CameraThread thread = mWeakThread.get();
			return thread != null ? thread.isRecording() :false;
		}

		public void openCamera(UsbControlBlock ctrlBlock) {
			sendMessage(obtainMessage(MSG_OPEN, ctrlBlock));
		}

		public void closeCamera() {
			stopPreview();
			sendEmptyMessage(MSG_CLOSE);
		}

		public void startPreview(Surface sureface) {
			if (sureface != null)
				sendMessage(obtainMessage(MSG_PREVIEW_START, sureface));
		}

		public void stopPreview() {
			stopRecording();
			final CameraThread thread = mWeakThread.get();
			if (thread == null) return;
			synchronized (thread.mSync) {
				sendEmptyMessage(MSG_PREVIEW_STOP);
				// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
				// while preview is still running.
				// therefore this method will take a time to execute
				try {
					thread.mSync.wait();
				} catch (InterruptedException e) {
				}
			}
		}

		public void captureStill() {
			sendEmptyMessage(MSG_CAPTURE_STILL);
		}

		public void startRecording() {
			sendEmptyMessage(MSG_CAPTURE_START);
		}

		public void stopRecording() {
			sendEmptyMessage(MSG_CAPTURE_STOP);
		}

/*		public void release() {
			sendEmptyMessage(MSG_RELEASE);
		} */

		@Override
		public void handleMessage(Message msg) {
			final CameraThread thread = mWeakThread.get();
			if (thread == null) return;
			switch (msg.what) {
			case MSG_OPEN:
				thread.handleOpen((UsbControlBlock)msg.obj);
				break;
			case MSG_CLOSE:
				thread.handleClose();
				break;
			case MSG_PREVIEW_START:
				thread.handleStartPreview((Surface)msg.obj);
				break;
			case MSG_PREVIEW_STOP:
				thread.handleStopPreview();
				break;
			case MSG_CAPTURE_STILL:
				thread.handleCaptureStill();
				break;
			case MSG_CAPTURE_START:
				thread.handleStartRecording();
				break;
			case MSG_CAPTURE_STOP:
				thread.handleStopRecording();
				break;
			case MSG_MEDIA_UPDATE:
				thread.handleUpdateMedia((String)msg.obj);
				break;
			case MSG_RELEASE:
				thread.handleRelease();
				break;
			default:
				throw new RuntimeException("unsupported message:what=" + msg.what);
			}
		}


		private static final class CameraThread extends Thread {
			private static final String TAG_THREAD = "CameraThread";
			private final Object mSync = new Object();
			private final WeakReference<MainActivity> mWeakParent;
			private final WeakReference<CameraViewInterface> mWeakCameraView;
			private boolean mIsRecording;
			/**
			 * shutter sound
			 */
			private SoundPool mSoundPool;
			private int mSoundId;
			private CameraHandler mHandler;
			/**
			 * for accessing UVC camera 
			 */
			private UVCCamera mUVCCamera;
			/**
			 * muxer for audio/video recording
			 */
			private MediaMuxerWrapper mMuxer;

			private CameraThread(MainActivity parent, CameraViewInterface cameraView) {
				super("CameraThread");
				mWeakParent = new WeakReference<MainActivity>(parent);
				mWeakCameraView = new WeakReference<CameraViewInterface>(cameraView);
				loadSutterSound(parent);
			}

			@Override
			protected void finalize() throws Throwable {
				Log.i(TAG, "CameraThread#finalize");
				super.finalize();
			}

			public CameraHandler getHandler() {
				if (DEBUG) Log.v(TAG_THREAD, "getHandler:");
				synchronized (mSync) {
					if (mHandler == null)
					try {
						mSync.wait();
					} catch (InterruptedException e) {
					}
				}
				return mHandler;
			}

			public boolean isCameraOpened() {
				synchronized (mSync) {
					return mUVCCamera != null;
				}
			}

			public boolean isRecording() {
				synchronized (mSync) {
					return (mUVCCamera != null) && (mMuxer != null);
				}
			}

			public void handleOpen(UsbControlBlock ctrlBlock) {
				if (DEBUG) Log.v(TAG_THREAD, "handleOpen:");
				handleClose();
				final UVCCamera camera = new UVCCamera();
				camera.open(ctrlBlock);
				synchronized (mSync) {
					mUVCCamera = camera;
				}
			}

			public void handleClose() {
				if (DEBUG) Log.v(TAG_THREAD, "handleClose:");
				handleStopRecording();
				final UVCCamera camera;
				synchronized (mSync) {
					camera = mUVCCamera;
					mUVCCamera = null;
				}
				if (camera != null) {
					camera.stopPreview();
					camera.destroy();
				}
			}

			public void handleStartPreview(Surface surface) {
				if (DEBUG) Log.v(TAG_THREAD, "handleStartPreview:");
				synchronized (mSync) {
					if (mUVCCamera == null) return;
					mUVCCamera.setPreviewDisplay(surface);
					mUVCCamera.startPreview();
				}
			}

			public void handleStopPreview() {
				if (DEBUG) Log.v(TAG_THREAD, "handleStopPreview:");
				synchronized (mSync) {
					if (mUVCCamera != null) {
						mUVCCamera.stopPreview();
					}
					mSync.notifyAll();
				}
			}

			public void handleCaptureStill() {
				if (DEBUG) Log.v(TAG_THREAD, "handleCaptureStill:");
				final MainActivity parent = mWeakParent.get();
				if (parent == null) return;
				mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);	// play shutter sound
				final Bitmap bitmap = mWeakCameraView.get().captureStillImage();
				try {
					// get buffered output stream for saving a captured still image as a file on external storage.
					// the file name is came from current time.
					// You should use extension name as same as CompressFormat when calling Bitmap#compress.
					final File outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png"); 
					final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
					try {
						try {
							bitmap.compress(CompressFormat.PNG, 100, os);
							os.flush();
							mHandler.sendMessage(mHandler.obtainMessage(MSG_MEDIA_UPDATE, outputFile.getPath()));
						} catch (IOException e) {
						}
					} finally {
						os.close();
					}
				} catch (FileNotFoundException e) {
				} catch (IOException e) {
				}
			}

			public void handleStartRecording() {
				if (DEBUG) Log.v(TAG_THREAD, "handleStartRecording:");
				try {
					synchronized (mSync) {
						if ((mUVCCamera == null) || (mMuxer != null)) return;
						mMuxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
					}
					if (USE_SURFACE_ENCODER) {
						// for video capturing using MediaSurfaceEncoder 
						new MediaSurfaceEncoder(mMuxer, mMediaEncoderListener);
					} else {
						// for video capturing using MediaVideoEncoder 
						new MediaVideoEncoder(mMuxer, mMediaEncoderListener);
					}
					if (true) {
						// for audio capturing
						new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
					}
					mMuxer.prepare();
					mMuxer.startRecording();
				} catch (IOException e) {
					Log.e(TAG, "startCapture:", e);
				}
			}

			public void handleStopRecording() {
				if (DEBUG) Log.v(TAG_THREAD, "handleStopRecording:mMuxer=" + mMuxer);
				final MediaMuxerWrapper muxer;
				synchronized (mSync) {
					muxer = mMuxer;
					mMuxer = null;
				}
				if (muxer != null) {
					muxer.stopRecording();
					// you should not wait here
				}
			}

			public void handleUpdateMedia(final String path) {
				if (DEBUG) Log.v(TAG_THREAD, "handleUpdateMedia:path=" + path);
				final MainActivity parent = mWeakParent.get();
				if (parent != null && parent.getApplicationContext() != null) {
					try {
						if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
						MediaScannerConnection.scanFile(parent.getApplicationContext(), new String[]{ path }, null, null);
					} catch (Exception e) {
						Log.e(TAG, "handleUpdateMedia:", e);
					}
					if (parent.isDestroyed())
						handleRelease();
				} else {
					Log.w(TAG, "MainActivity already destroyed");
					// give up to add this movice to MediaStore now.
					// Seeing this movie on Gallery app etc. will take a lot of time. 
					handleRelease();
				}
			}

			public void handleRelease() {
				if (DEBUG) Log.v(TAG_THREAD, "handleRelease:");
 				handleClose();
				if (!mIsRecording)
					Looper.myLooper().quit();
			}

			private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
				@Override
				public void onPrepared(MediaEncoder encoder) {
					if (DEBUG) Log.v(TAG, "onPrepared:encoder=" + encoder);
					mIsRecording = true;
					if (encoder instanceof MediaVideoEncoder)
					try {
						mWeakCameraView.get().setVideoEncoder(encoder);
					} catch (Exception e) {
						Log.e(TAG, "onPrepared:", e);
					}
					if (encoder instanceof MediaSurfaceEncoder)
					try {
						mWeakCameraView.get().setVideoEncoder(encoder);
						mUVCCamera.startCapture(((MediaSurfaceEncoder)encoder).getInputSurface());
					} catch (Exception e) {
						Log.e(TAG, "onPrepared:", e);
					}
				}

				@Override
				public void onStopped(MediaEncoder encoder) {
					if (DEBUG) Log.v(TAG_THREAD, "onStopped:encoder=" + encoder);
					if ((encoder instanceof MediaVideoEncoder)
						|| (encoder instanceof MediaSurfaceEncoder))
					try {
						mIsRecording = false;
						final MainActivity parent = mWeakParent.get();
						mWeakCameraView.get().setVideoEncoder(null);
						mUVCCamera.stopCapture();
						final String path = encoder.getOutputPath();
						if (!TextUtils.isEmpty(path)) {
							mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_MEDIA_UPDATE, path), 1000);
						} else {
							if (parent == null || parent.isDestroyed()) {
								handleRelease();
							}
						}
					} catch (Exception e) {
						Log.e(TAG, "onPrepared:", e);
					}
				}
			};

			/**
			 * prepare and load shutter sound for still image capturing
			 */
			private void loadSutterSound(Context context) {
		    	// get system stream type using refrection
		        int streamType;
		        try {
		            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
		            final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
		            streamType = sseField.getInt(null);
		        } catch (Exception e) {
		        	streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
		        }
		        if (mSoundPool != null) {
		        	try {
		        		mSoundPool.release();
		        	} catch (Exception e) {
		        	}
		        	mSoundPool = null;
		        }
		        // load sutter sound from resource
			    mSoundPool = new SoundPool(2, streamType, 0);
			    mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
			}

			@Override
			public void run() {
				Looper.prepare();
				synchronized (mSync) {
					mHandler = new CameraHandler(this);
					mSync.notifyAll();
				}
				Looper.loop();
				synchronized (mSync) {
					mHandler = null;
					mSoundPool.release();
					mSoundPool = null;
					mSync.notifyAll();
				}
			}
		}
	}
}
