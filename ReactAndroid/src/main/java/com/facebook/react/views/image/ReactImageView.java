/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.Toast;
import androidx.annotation.Nullable;
import com.facebook.common.internal.Objects;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.drawee.controller.AbstractDraweeControllerBuilder;
import com.facebook.drawee.controller.ControllerListener;
import com.facebook.drawee.controller.ForwardingControllerListener;
import com.facebook.drawee.drawable.AutoRotateDrawable;
import com.facebook.drawee.drawable.RoundedColorDrawable;
import com.facebook.drawee.drawable.ScalingUtils;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder;
import com.facebook.drawee.generic.RoundingParams;
import com.facebook.drawee.view.GenericDraweeView;
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.postprocessors.IterativeBoxBlurPostProcessor;
import com.facebook.imagepipeline.request.BasePostprocessor;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.imagepipeline.request.Postprocessor;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.config.ReactFeatureFlags;
import com.facebook.react.modules.fresco.ReactNetworkImageRequest;
import com.facebook.react.uimanager.FloatUtil;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.imagehelper.ImageSource;
import com.facebook.react.views.imagehelper.MultiSourceHelper;
import com.facebook.react.views.imagehelper.MultiSourceHelper.MultiSourceResult;
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper;
import com.facebook.yoga.YogaConstants;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Wrapper class around Fresco's GenericDraweeView, enabling persisting props across multiple view
 * update and consistent processing of both static and network images.
 */
public class ReactImageView extends GenericDraweeView {

  public static final int REMOTE_IMAGE_FADE_DURATION_MS = 300;

  public static final String REMOTE_TRANSPARENT_BITMAP_URI =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

  private static float[] sComputedCornerRadii = new float[4];

  /*
   * Implementation note re rounded corners:
   *
   * Fresco's built-in rounded corners only work for 'cover' resize mode -
   * this is a limitation in Android itself. Fresco has a workaround for this, but
   * it requires knowing the background color.
   *
   * So for the other modes, we use a postprocessor.
   * Because the postprocessor uses a modified bitmap, that would just get cropped in
   * 'cover' mode, so we fall back to Fresco's normal implementation.
   */
  private static final Matrix sMatrix = new Matrix();
  private static final Matrix sInverse = new Matrix();
  private ImageResizeMethod mResizeMethod = ImageResizeMethod.AUTO;

  public void updateCallerContext(@Nullable Object callerContext) {
    if (!Objects.equal(mCallerContext, callerContext)) {
      mCallerContext = callerContext;
      mIsDirty = true;
    }
  }

  private class RoundedCornerPostprocessor extends BasePostprocessor {

    void getRadii(Bitmap source, float[] computedCornerRadii, float[] mappedRadii) {
      mScaleType.getTransform(
          sMatrix,
          new Rect(0, 0, source.getWidth(), source.getHeight()),
          source.getWidth(),
          source.getHeight(),
          0.0f,
          0.0f);
      sMatrix.invert(sInverse);

      mappedRadii[0] = sInverse.mapRadius(computedCornerRadii[0]);
      mappedRadii[1] = mappedRadii[0];

      mappedRadii[2] = sInverse.mapRadius(computedCornerRadii[1]);
      mappedRadii[3] = mappedRadii[2];

      mappedRadii[4] = sInverse.mapRadius(computedCornerRadii[2]);
      mappedRadii[5] = mappedRadii[4];

      mappedRadii[6] = sInverse.mapRadius(computedCornerRadii[3]);
      mappedRadii[7] = mappedRadii[6];
    }

    @Override
    public void process(Bitmap output, Bitmap source) {
      getCornerRadii(sComputedCornerRadii);

      output.setHasAlpha(true);
      if (FloatUtil.floatsEqual(sComputedCornerRadii[0], 0f)
          && FloatUtil.floatsEqual(sComputedCornerRadii[1], 0f)
          && FloatUtil.floatsEqual(sComputedCornerRadii[2], 0f)
          && FloatUtil.floatsEqual(sComputedCornerRadii[3], 0f)) {
        super.process(output, source);
        return;
      }
      Paint paint = new Paint();
      paint.setAntiAlias(true);
      paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
      Canvas canvas = new Canvas(output);

      float[] radii = new float[8];

      getRadii(source, sComputedCornerRadii, radii);

      Path pathForBorderRadius = new Path();

      pathForBorderRadius.addRoundRect(
          new RectF(0, 0, source.getWidth(), source.getHeight()), radii, Path.Direction.CW);

      canvas.drawPath(pathForBorderRadius, paint);
    }
  }

  // Fresco lacks support for repeating images, see https://github.com/facebook/fresco/issues/1575
  // We implement it here as a postprocessing step.
  private static final Matrix sTileMatrix = new Matrix();

  private class TilePostprocessor extends BasePostprocessor {
    @Override
    public CloseableReference<Bitmap> process(Bitmap source, PlatformBitmapFactory bitmapFactory) {
      final Rect destRect = new Rect(0, 0, getWidth(), getHeight());
      mScaleType.getTransform(
          sTileMatrix, destRect, source.getWidth(), source.getHeight(), 0.0f, 0.0f);

      Paint paint = new Paint();
      paint.setAntiAlias(true);
      Shader shader = new BitmapShader(source, mTileMode, mTileMode);
      shader.setLocalMatrix(sTileMatrix);
      paint.setShader(shader);

      CloseableReference<Bitmap> output = bitmapFactory.createBitmap(getWidth(), getHeight());
      try {
        Canvas canvas = new Canvas(output.get());
        canvas.drawRect(destRect, paint);
        return output.clone();
      } finally {
        CloseableReference.closeSafely(output);
      }
    }
  }

  private final List<ImageSource> mSources = new LinkedList<>();

  private @Nullable ImageSource mImageSource;
  private @Nullable ImageSource mCachedImageSource;
  private @Nullable Drawable mDefaultImageDrawable;
  private @Nullable Drawable mLoadingImageDrawable;
  private @Nullable RoundedColorDrawable mBackgroundImageDrawable;
  private int mBackgroundColor = 0x00000000;
  private int mBorderColor;
  private int mOverlayColor;
  private float mBorderWidth;
  private float mBorderRadius = YogaConstants.UNDEFINED;
  private @Nullable float[] mBorderCornerRadii;
  private ScalingUtils.ScaleType mScaleType = ImageResizeMode.defaultValue();
  private Shader.TileMode mTileMode = ImageResizeMode.defaultTileMode();
  private boolean mIsDirty;
  private final AbstractDraweeControllerBuilder mDraweeControllerBuilder;
  private @Nullable RoundedCornerPostprocessor mRoundedCornerPostprocessor;
  private @Nullable TilePostprocessor mTilePostprocessor;
  private @Nullable IterativeBoxBlurPostProcessor mIterativeBoxBlurPostProcessor;
  private @Nullable ReactImageDownloadListener mDownloadListener;
  private @Nullable ControllerListener mControllerForTesting;
  private @Nullable GlobalImageLoadListener mGlobalImageLoadListener;
  private @Nullable Object mCallerContext;
  private int mFadeDurationMs = -1;
  private boolean mProgressiveRenderingEnabled;
  private ReadableMap mHeaders;

  // We can't specify rounding in XML, so have to do so here
  private static GenericDraweeHierarchy buildHierarchy(Context context) {
    return new GenericDraweeHierarchyBuilder(context.getResources())
        .setRoundingParams(RoundingParams.fromCornersRadius(0))
        .build();
  }

  public ReactImageView(
      Context context,
      AbstractDraweeControllerBuilder draweeControllerBuilder,
      @Nullable GlobalImageLoadListener globalImageLoadListener,
      @Nullable Object callerContext) {
    super(context, buildHierarchy(context));
    mDraweeControllerBuilder = draweeControllerBuilder;
    mGlobalImageLoadListener = globalImageLoadListener;
    mCallerContext = callerContext;
  }

  public void setShouldNotifyLoadEvents(boolean shouldNotify) {
    // Skip update if shouldNotify is already in sync with the download listener
    if (shouldNotify == (mDownloadListener != null)) {
      return;
    }

    if (!shouldNotify) {
      mDownloadListener = null;
    } else {
      final EventDispatcher mEventDispatcher =
          UIManagerHelper.getEventDispatcherForReactTag((ReactContext) getContext(), getId());

      mDownloadListener =
          new ReactImageDownloadListener<ImageInfo>() {
            @Override
            public void onProgressChange(int loaded, int total) {
              // TODO: Somehow get image size and convert `loaded` and `total` to image bytes.
              mEventDispatcher.dispatchEvent(
                  ImageLoadEvent.createProgressEvent(
                      UIManagerHelper.getSurfaceId(ReactImageView.this),
                      getId(),
                      mImageSource.getSource(),
                      loaded,
                      total));
            }

            @Override
            public void onSubmit(String id, Object callerContext) {
              mEventDispatcher.dispatchEvent(
                  ImageLoadEvent.createLoadStartEvent(
                      UIManagerHelper.getSurfaceId(ReactImageView.this), getId()));
            }

            @Override
            public void onFinalImageSet(
                String id, @Nullable final ImageInfo imageInfo, @Nullable Animatable animatable) {
              if (imageInfo != null) {
                mEventDispatcher.dispatchEvent(
                    ImageLoadEvent.createLoadEvent(
                        UIManagerHelper.getSurfaceId(ReactImageView.this),
                        getId(),
                        mImageSource.getSource(),
                        imageInfo.getWidth(),
                        imageInfo.getHeight()));
                mEventDispatcher.dispatchEvent(
                    ImageLoadEvent.createLoadEndEvent(
                        UIManagerHelper.getSurfaceId(ReactImageView.this), getId()));
              }
            }

            @Override
            public void onFailure(String id, Throwable throwable) {
              mEventDispatcher.dispatchEvent(
                  ImageLoadEvent.createErrorEvent(
                      UIManagerHelper.getSurfaceId(ReactImageView.this), getId(), throwable));
            }
          };
    }

    mIsDirty = true;
  }

  public void setBlurRadius(float blurRadius) {
    // Divide `blurRadius` by 2 to more closely match other platforms.
    int pixelBlurRadius = (int) PixelUtil.toPixelFromDIP(blurRadius) / 2;
    if (pixelBlurRadius == 0) {
      mIterativeBoxBlurPostProcessor = null;
    } else {
      mIterativeBoxBlurPostProcessor = new IterativeBoxBlurPostProcessor(2, pixelBlurRadius);
    }
    mIsDirty = true;
  }

  @Override
  public void setBackgroundColor(int backgroundColor) {
    if (mBackgroundColor != backgroundColor) {
      mBackgroundColor = backgroundColor;
      mBackgroundImageDrawable = new RoundedColorDrawable(backgroundColor);
      mIsDirty = true;
    }
  }

  public void setBorderColor(int borderColor) {
    if (mBorderColor != borderColor) {
      mBorderColor = borderColor;
      mIsDirty = true;
    }
  }

  public void setOverlayColor(int overlayColor) {
    if (mOverlayColor != overlayColor) {
      mOverlayColor = overlayColor;
      mIsDirty = true;
    }
  }

  public void setBorderWidth(float borderWidth) {
    float newBorderWidth = PixelUtil.toPixelFromDIP(borderWidth);
    if (!FloatUtil.floatsEqual(mBorderWidth, newBorderWidth)) {
      mBorderWidth = newBorderWidth;
      mIsDirty = true;
    }
  }

  public void setBorderRadius(float borderRadius) {
    if (!FloatUtil.floatsEqual(mBorderRadius, borderRadius)) {
      mBorderRadius = borderRadius;
      mIsDirty = true;
    }
  }

  public void setBorderRadius(float borderRadius, int position) {
    if (mBorderCornerRadii == null) {
      mBorderCornerRadii = new float[4];
      Arrays.fill(mBorderCornerRadii, YogaConstants.UNDEFINED);
    }

    if (!FloatUtil.floatsEqual(mBorderCornerRadii[position], borderRadius)) {
      mBorderCornerRadii[position] = borderRadius;
      mIsDirty = true;
    }
  }

  public void setScaleType(ScalingUtils.ScaleType scaleType) {
    if (mScaleType != scaleType) {
      mScaleType = scaleType;
      if (shouldUseRoundedCornerPostprocessing()) {
        mRoundedCornerPostprocessor = new RoundedCornerPostprocessor();
      } else {
        mRoundedCornerPostprocessor = null;
      }
      mIsDirty = true;
    }
  }

  public void setTileMode(Shader.TileMode tileMode) {
    if (mTileMode != tileMode) {
      mTileMode = tileMode;
      if (isTiled()) {
        mTilePostprocessor = new TilePostprocessor();
      } else {
        mTilePostprocessor = null;
      }
      mIsDirty = true;
    }
  }

  public void setResizeMethod(ImageResizeMethod resizeMethod) {
    if (mResizeMethod != resizeMethod) {
      mResizeMethod = resizeMethod;
      mIsDirty = true;
    }
  }

  public void setSource(@Nullable ReadableArray sources) {
    List<ImageSource> tmpSources = new LinkedList<>();

    if (sources == null || sources.size() == 0) {
      ImageSource imageSource = new ImageSource(getContext(), REMOTE_TRANSPARENT_BITMAP_URI);
      tmpSources.add(imageSource);
    } else {
      // Optimize for the case where we have just one uri, case in which we don't need the sizes
      if (sources.size() == 1) {
        ReadableMap source = sources.getMap(0);
        String uri = source.getString("uri");
        ImageSource imageSource = new ImageSource(getContext(), uri);
        tmpSources.add(imageSource);
        if (Uri.EMPTY.equals(imageSource.getUri())) {
          warnImageSource(uri);
        }
      } else {
        for (int idx = 0; idx < sources.size(); idx++) {
          ReadableMap source = sources.getMap(idx);
          String uri = source.getString("uri");
          ImageSource imageSource =
              new ImageSource(
                  getContext(), uri, source.getDouble("width"), source.getDouble("height"));
          tmpSources.add(imageSource);
          if (Uri.EMPTY.equals(imageSource.getUri())) {
            warnImageSource(uri);
          }
        }
      }
    }

    // Don't reset sources and dirty node if sources haven't changed
    if (mSources.equals(tmpSources)) {
      return;
    }

    mSources.clear();
    for (ImageSource src : tmpSources) {
      mSources.add(src);
    }
    mIsDirty = true;
  }

  public void setDefaultSource(@Nullable String name) {
    Drawable newDefaultDrawable =
        ResourceDrawableIdHelper.getInstance().getResourceDrawable(getContext(), name);
    if (!Objects.equal(mDefaultImageDrawable, newDefaultDrawable)) {
      mDefaultImageDrawable = newDefaultDrawable;
      mIsDirty = true;
    }
  }

  public void setLoadingIndicatorSource(@Nullable String name) {
    Drawable drawable =
        ResourceDrawableIdHelper.getInstance().getResourceDrawable(getContext(), name);
    Drawable newLoadingIndicatorSource =
        drawable != null ? (Drawable) new AutoRotateDrawable(drawable, 1000) : null;
    if (!Objects.equal(mLoadingImageDrawable, newLoadingIndicatorSource)) {
      mLoadingImageDrawable = newLoadingIndicatorSource;
      mIsDirty = true;
    }
  }

  public void setProgressiveRenderingEnabled(boolean enabled) {
    mProgressiveRenderingEnabled = enabled;
    // no worth marking as dirty if it already rendered..
  }

  public void setFadeDuration(int durationMs) {
    mFadeDurationMs = durationMs;
    // no worth marking as dirty if it already rendered..
  }

  private void getCornerRadii(float[] computedCorners) {
    float defaultBorderRadius = !YogaConstants.isUndefined(mBorderRadius) ? mBorderRadius : 0;

    computedCorners[0] =
        mBorderCornerRadii != null && !YogaConstants.isUndefined(mBorderCornerRadii[0])
            ? mBorderCornerRadii[0]
            : defaultBorderRadius;
    computedCorners[1] =
        mBorderCornerRadii != null && !YogaConstants.isUndefined(mBorderCornerRadii[1])
            ? mBorderCornerRadii[1]
            : defaultBorderRadius;
    computedCorners[2] =
        mBorderCornerRadii != null && !YogaConstants.isUndefined(mBorderCornerRadii[2])
            ? mBorderCornerRadii[2]
            : defaultBorderRadius;
    computedCorners[3] =
        mBorderCornerRadii != null && !YogaConstants.isUndefined(mBorderCornerRadii[3])
            ? mBorderCornerRadii[3]
            : defaultBorderRadius;
  }

  public void setHeaders(ReadableMap headers) {
    mHeaders = headers;
  }

  public void maybeUpdateView() {
    if (!mIsDirty) {
      return;
    }

    if (hasMultipleSources() && (getWidth() <= 0 || getHeight() <= 0)) {
      // If we need to choose from multiple uris but the size is not yet set, wait for layout pass
      return;
    }

    setSourceImage();
    if (mImageSource == null) {
      return;
    }

    boolean doResize = shouldResize(mImageSource);
    if (doResize && (getWidth() <= 0 || getHeight() <= 0)) {
      // If need a resize and the size is not yet set, wait until the layout pass provides one
      return;
    }

    if (isTiled() && (getWidth() <= 0 || getHeight() <= 0)) {
      // If need to tile and the size is not yet set, wait until the layout pass provides one
      return;
    }

    GenericDraweeHierarchy hierarchy = getHierarchy();
    hierarchy.setActualImageScaleType(mScaleType);

    if (mDefaultImageDrawable != null) {
      hierarchy.setPlaceholderImage(mDefaultImageDrawable, mScaleType);
    }

    if (mLoadingImageDrawable != null) {
      hierarchy.setPlaceholderImage(mLoadingImageDrawable, ScalingUtils.ScaleType.CENTER);
    }

    getCornerRadii(sComputedCornerRadii);

    RoundingParams roundingParams = hierarchy.getRoundingParams();
    roundingParams.setCornersRadii(
        sComputedCornerRadii[0],
        sComputedCornerRadii[1],
        sComputedCornerRadii[2],
        sComputedCornerRadii[3]);

    if (mBackgroundImageDrawable != null) {
      mBackgroundImageDrawable.setBorder(mBorderColor, mBorderWidth);
      mBackgroundImageDrawable.setRadii(roundingParams.getCornersRadii());
      hierarchy.setBackgroundImage(mBackgroundImageDrawable);
    }
    if (shouldUseRoundedCornerPostprocessing()) {
      roundingParams.setCornersRadius(0);
    }
    roundingParams.setBorder(mBorderColor, mBorderWidth);
    if (mOverlayColor != Color.TRANSPARENT) {
      roundingParams.setOverlayColor(mOverlayColor);
    } else {
      // make sure the default rounding method is used.
      roundingParams.setRoundingMethod(RoundingParams.RoundingMethod.BITMAP_ONLY);
    }
    hierarchy.setRoundingParams(roundingParams);
    hierarchy.setFadeDuration(
        mFadeDurationMs >= 0
            ? mFadeDurationMs
            : mImageSource.isResource() ? 0 : REMOTE_IMAGE_FADE_DURATION_MS);

    List<Postprocessor> postprocessors = new LinkedList<>();
    if (mRoundedCornerPostprocessor != null) {
      postprocessors.add(mRoundedCornerPostprocessor);
    }
    if (mIterativeBoxBlurPostProcessor != null) {
      postprocessors.add(mIterativeBoxBlurPostProcessor);
    }
    if (mTilePostprocessor != null) {
      postprocessors.add(mTilePostprocessor);
    }
    Postprocessor postprocessor = MultiPostprocessor.from(postprocessors);

    ResizeOptions resizeOptions = doResize ? new ResizeOptions(getWidth(), getHeight()) : null;

    ImageRequestBuilder imageRequestBuilder =
        ImageRequestBuilder.newBuilderWithSource(mImageSource.getUri())
            .setPostprocessor(postprocessor)
            .setResizeOptions(resizeOptions)
            .setAutoRotateEnabled(true)
            .setProgressiveRenderingEnabled(mProgressiveRenderingEnabled);

    ImageRequest imageRequest =
        ReactNetworkImageRequest.fromBuilderWithHeaders(imageRequestBuilder, mHeaders);

    if (mGlobalImageLoadListener != null) {
      mGlobalImageLoadListener.onLoadAttempt(mImageSource.getUri());
    }

    // This builder is reused
    mDraweeControllerBuilder.reset();

    mDraweeControllerBuilder
        .setAutoPlayAnimations(true)
        .setCallerContext(mCallerContext)
        .setOldController(getController())
        .setImageRequest(imageRequest);

    if (mCachedImageSource != null) {
      ImageRequest cachedImageRequest =
          ImageRequestBuilder.newBuilderWithSource(mCachedImageSource.getUri())
              .setPostprocessor(postprocessor)
              .setResizeOptions(resizeOptions)
              .setAutoRotateEnabled(true)
              .setProgressiveRenderingEnabled(mProgressiveRenderingEnabled)
              .build();
      mDraweeControllerBuilder.setLowResImageRequest(cachedImageRequest);
    }

    if (mDownloadListener != null && mControllerForTesting != null) {
      ForwardingControllerListener combinedListener = new ForwardingControllerListener();
      combinedListener.addListener(mDownloadListener);
      combinedListener.addListener(mControllerForTesting);
      mDraweeControllerBuilder.setControllerListener(combinedListener);
    } else if (mControllerForTesting != null) {
      mDraweeControllerBuilder.setControllerListener(mControllerForTesting);
    } else if (mDownloadListener != null) {
      mDraweeControllerBuilder.setControllerListener(mDownloadListener);
    }

    if (mDownloadListener != null) {
      hierarchy.setProgressBarImage(mDownloadListener);
    }

    setController(mDraweeControllerBuilder.build());
    mIsDirty = false;

    // Reset again so the DraweeControllerBuilder clears all it's references. Otherwise, this causes
    // a memory leak.
    mDraweeControllerBuilder.reset();
  }

  // VisibleForTesting
  public void setControllerListener(ControllerListener controllerListener) {
    mControllerForTesting = controllerListener;
    mIsDirty = true;
    maybeUpdateView();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (w > 0 && h > 0) {
      mIsDirty = mIsDirty || hasMultipleSources() || isTiled();
      maybeUpdateView();
    }
  }

  /** ReactImageViews only render a single image. */
  @Override
  public boolean hasOverlappingRendering() {
    return false;
  }

  private boolean hasMultipleSources() {
    return mSources.size() > 1;
  }

  private boolean isTiled() {
    return mTileMode != Shader.TileMode.CLAMP;
  }

  private boolean shouldUseRoundedCornerPostprocessing() {
    return mScaleType != ScalingUtils.ScaleType.CENTER_CROP
        && mScaleType != ScalingUtils.ScaleType.FOCUS_CROP
        && ReactFeatureFlags.enableRoundedCornerPostprocessing;
  }

  private void setSourceImage() {
    mImageSource = null;
    if (mSources.isEmpty()) {
      ImageSource imageSource = new ImageSource(getContext(), REMOTE_TRANSPARENT_BITMAP_URI);
      mSources.add(imageSource);
    } else if (hasMultipleSources()) {
      MultiSourceResult multiSource =
          MultiSourceHelper.getBestSourceForSize(getWidth(), getHeight(), mSources);
      mImageSource = multiSource.getBestResult();
      mCachedImageSource = multiSource.getBestResultInCache();
      return;
    }

    mImageSource = mSources.get(0);
  }

  private boolean shouldResize(ImageSource imageSource) {
    // Resizing is inferior to scaling. See http://frescolib.org/docs/resizing-rotating.html#_
    // We resize here only for images likely to be from the device's camera, where the app developer
    // has no control over the original size
    if (mResizeMethod == ImageResizeMethod.AUTO) {
      return UriUtil.isLocalContentUri(imageSource.getUri())
          || UriUtil.isLocalFileUri(imageSource.getUri());
    } else if (mResizeMethod == ImageResizeMethod.RESIZE) {
      return true;
    } else {
      return false;
    }
  }

  private void warnImageSource(String uri) {
    if (ReactBuildConfig.DEBUG) {
      Toast.makeText(
              getContext(),
              "Warning: Image source \"" + uri + "\" doesn't exist",
              Toast.LENGTH_SHORT)
          .show();
    }
  }
}
