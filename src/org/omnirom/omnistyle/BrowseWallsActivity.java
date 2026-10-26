/*
 *  Copyright (C) 2017 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.omnirom.omnistyle;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrowseWallsActivity extends AppCompatActivity {

    private static final String TAG = "BrowseWallsActivity";
    private static final String IMAGE_TYPE = "image/*";
    private static final int IMAGE_CROP_AND_SET = 1;
    private static final String EMPTY_CREATOR = "ZZZ";

    private static final int SORT_BY_DEFAULT = 0;
    private static final int SORT_BY_CREATOR = 1;
    private static final int SORT_BY_TAG = 2;

    private static final boolean DEBUG = false;
    private List<WallpaperInfo> mWallpaperList;
    private Resources mRes;
    private RecyclerView mWallpaperView;
    private String mPackageName;
    private WallpaperListAdapter mAdapter;
    private int mWallpaperPreviewSize;
    private int mSortType = SORT_BY_DEFAULT;
    private MenuItem mMenuItem;

    // Thumbnail loading (replaces Picasso, which was only used for local drawables).
    private final ExecutorService mThumbExecutor = Executors.newFixedThreadPool(2);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> mThumbCache =
            new LruCache<Integer, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };

    private class WallpaperInfo implements Comparable<WallpaperInfo> {
        public String mImage;
        public String mCreator;
        public String mDisplayName;
        public String mTag;

        @Override
        public int compareTo(WallpaperInfo o) {
            if (mSortType == SORT_BY_CREATOR && mCreator != null) {
                return mCreator.compareToIgnoreCase(o.mCreator);
            }
            if (mSortType == SORT_BY_TAG && mTag != null) {
                return mTag.compareToIgnoreCase(o.mTag);
            }
            if (mSortType == SORT_BY_DEFAULT && mDisplayName != null) {
                return mDisplayName.compareToIgnoreCase(o.mDisplayName);
            }
            return 0;
        }
    }

    class ImageHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        public View rootView;
        public ImageView mWallpaperImage;
        public TextView mWallpaperName;
        public TextView mWallpaperCreator;

        ImageHolder(View itemView) {
            super(itemView);
            rootView = itemView;
            mWallpaperImage = (ImageView) itemView.findViewById(R.id.wallpaper_image);
            mWallpaperName = (TextView) itemView.findViewById(R.id.wallpaper_name);
            mWallpaperCreator = (TextView) itemView.findViewById(R.id.wallpaper_creator);
            rootView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                return;
            }
            if (!checkCropActivity()) {
                new AlertDialog.Builder(BrowseWallsActivity.this)
                        .setMessage(R.string.no_crop_activity_dialog_text)
                        .setTitle(R.string.no_crop_activity_dialog_title)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            doSetLocalWallpaper(position);
        }
    }

    public class WallpaperListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.wallpaper_image, parent, false);
            return new ImageHolder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            final ImageHolder holder = (ImageHolder) h;

            try {
                WallpaperInfo wi = mWallpaperList.get(position);
                int resId = mRes.getIdentifier(wi.mImage, "drawable", mPackageName);

                loadThumbnail(holder.mWallpaperImage, resId);

                holder.mWallpaperName.setVisibility(TextUtils.isEmpty(wi.mDisplayName) ? View.GONE : View.VISIBLE);
                holder.mWallpaperName.setText(wi.mDisplayName);
                holder.mWallpaperCreator.setVisibility(TextUtils.isEmpty(wi.mCreator) || wi.mCreator.equals(EMPTY_CREATOR) ? View.GONE : View.VISIBLE);
                holder.mWallpaperCreator.setText(wi.mCreator);
            } catch (Exception e) {
                holder.mWallpaperImage.setImageDrawable(null);
            }
        }

        @Override
        public int getItemCount() {
            return mWallpaperList.size();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.content_wallpapers);
        mPackageName = getClass().getPackage().getName();

        mWallpaperList = new ArrayList<WallpaperInfo>();
        mWallpaperPreviewSize = 300; // in pixel

        PackageManager packageManager = getPackageManager();
        try {
            mRes = packageManager.getResourcesForApplication(mPackageName);
            getAvailableWallpapers();

            mWallpaperView = (RecyclerView) findViewById(R.id.wallpaper_images);
            mWallpaperView.setLayoutManager(new GridLayoutManager(this, 2));
            mWallpaperView.setHasFixedSize(false);

            mAdapter = new WallpaperListAdapter();
            mWallpaperView.setAdapter(mAdapter);
            sortWallpapers();
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMAGE_CROP_AND_SET && resultCode == RESULT_OK) {
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.sort_by) {
            mSortType = (mSortType == SORT_BY_CREATOR) ? SORT_BY_DEFAULT : SORT_BY_CREATOR;
            sortWallpapers();
            invalidateOptionsMenu();
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mMenuItem != null) {
            mMenuItem.setTitle(mSortType == SORT_BY_CREATOR
                    ? R.string.sort_by_default_menu : R.string.sort_by_creator_menu);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void getAvailableWallpapers() throws XmlPullParserException, IOException {
        mWallpaperList.clear();
        InputStream in = null;
        XmlPullParser parser = null;

        try {
            in = mRes.getAssets().open("wallpapers.xml");
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            parser = factory.newPullParser();
            parser.setInput(in, "UTF-8");
            loadResourcesFromXmlParser(parser);
        } finally {
            if (parser instanceof XmlResourceParser) {
                ((XmlResourceParser) parser).close();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void loadResourcesFromXmlParser(XmlPullParser parser) throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        do {
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            if (name.equalsIgnoreCase("wallpaper")) {
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    String creator = parser.getAttributeValue(null, "creator");
                    String displayName = parser.getAttributeValue(null, "name");
                    WallpaperInfo wi = new WallpaperInfo();
                    wi.mImage = image;
                    wi.mCreator = TextUtils.isEmpty(creator) ? EMPTY_CREATOR : creator;
                    wi.mDisplayName = TextUtils.isEmpty(displayName) ? "" : displayName;
                    wi.mTag = "";
                    if (DEBUG)
                        Log.i(TAG, "add wallpaper " + image + " " + mRes.getIdentifier(image, "drawable", mPackageName));
                    mWallpaperList.add(wi);
                }
            }
        } while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT);
        if (DEBUG) Log.i(TAG, "loaded size = " + mWallpaperList.size());
    }

    private Intent getCropActivity() {
        final Intent cropAndSetWallpaperIntent = new Intent();
        cropAndSetWallpaperIntent.setComponent(new ComponentName("com.android.gallery3d",
                "com.android.gallery3d.filtershow.crop.CropActivity"));
        return cropAndSetWallpaperIntent;
    }

    private boolean checkCropActivity() {
        final Intent cropAndSetWallpaperIntent = getCropActivity();
        return cropAndSetWallpaperIntent.resolveActivityInfo(getPackageManager(), 0) != null;
    }

    private void doCallCropActivity(Uri imageUri, Point dispSize, int wpWidth, int wpHeight) {
        float spotlightX = (float) dispSize.x / wpWidth;
        float spotlightY = (float) dispSize.y / wpHeight;

        final Intent cropAndSetWallpaperIntent = getCropActivity()
                .setDataAndType(imageUri, IMAGE_TYPE)
                .putExtra(CropExtras.KEY_OUTPUT_X, wpWidth)
                .putExtra(CropExtras.KEY_OUTPUT_Y, wpHeight)
                .putExtra(CropExtras.KEY_ASPECT_X, wpWidth)
                .putExtra(CropExtras.KEY_ASPECT_Y, wpHeight)
                .putExtra(CropExtras.KEY_SPOTLIGHT_X, spotlightX)
                .putExtra(CropExtras.KEY_SPOTLIGHT_Y, spotlightY)
                .putExtra(CropExtras.KEY_SCALE, true)
                .putExtra(CropExtras.KEY_SCALE_UP_IF_NEEDED, true);

        new AlertDialog.Builder(BrowseWallsActivity.this)
                .setTitle(R.string.wallpaper_type_dialog_title)
                .setItems(R.array.wallpaper_type_list, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int item) {
                        int wallpaperType = CropExtras.DEFAULT_WALLPAPER_TYPE;
                        if (item == 1) {
                            wallpaperType = WallpaperManager.FLAG_SYSTEM;
                        } else if (item == 2) {
                            wallpaperType = WallpaperManager.FLAG_LOCK;
                        }
                        cropAndSetWallpaperIntent.putExtra(CropExtras.KEY_SET_AS_WALLPAPER, true)
                                .putExtra(CropExtras.KEY_WALLPAPER_TYPE, wallpaperType);
                        startActivityForResult(cropAndSetWallpaperIntent, IMAGE_CROP_AND_SET);
                    }
                })
                .show();
    }

    private void doSetLocalWallpaper(final int position) {
        WallpaperManager wpm = WallpaperManager.getInstance(getApplicationContext());
        final int wpWidth = wpm.getDesiredMinimumWidth();
        final int wpHeight = wpm.getDesiredMinimumHeight();
        Display disp = getWindowManager().getDefaultDisplay();
        final Point dispSize = new Point();
        disp.getRealSize(dispSize);

        WallpaperInfo wi = mWallpaperList.get(position);
        final int resId = mRes.getIdentifier(wi.mImage, "drawable", mPackageName);
        if (resId == 0) {
            Log.e(TAG, "Wallpaper resource undefined for position = " + position);
            return;
        }
        final Uri uri = Uri.parse("android.resource://" + mPackageName + "/" + resId);
        Drawable image = mRes.getDrawable(resId, null);
        if (DEBUG) Log.d(TAG, "crop uri = " + uri);

        // if that image ratio is close to the display size ratio
        // assume this wall is meant to be fullscreen without scrolling
        float displayRatio = (float) Math.round(((float) dispSize.x / dispSize.y) * 10) / 10;
        float imageRatio = (float) Math.round(((float) image.getIntrinsicWidth() / image.getIntrinsicHeight()) * 10) / 10;
        if (displayRatio != imageRatio) {
            // ask if scrolling wallpaper should be used original size
            // or if it should be cropped to image size
            new AlertDialog.Builder(BrowseWallsActivity.this)
                    .setMessage(R.string.scrolling_wall_dialog_text)
                    .setTitle(R.string.scrolling_wall_dialog_title)
                    .setCancelable(false)
                    .setPositiveButton(R.string.scrolling_wall_yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            doCallCropActivity(uri, dispSize, wpWidth, wpHeight);
                        }
                    })
                    .setNegativeButton(R.string.scrolling_wall_no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            doCallCropActivity(uri, dispSize, dispSize.x, dispSize.y);
                        }
                    })
                    .show();
        } else {
            doCallCropActivity(uri, dispSize, dispSize.x, dispSize.y);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int spanCount = getResources().getInteger(R.integer.wallpapers_column_count);
        GridLayoutManager manager = (GridLayoutManager) mWallpaperView.getLayoutManager();
        manager.setSpanCount(spanCount);
        manager.requestLayout();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.sort_menu, menu);
        mMenuItem = menu.findItem(R.id.sort_by);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mThumbExecutor.shutdownNow();
    }

    private void loadThumbnail(final ImageView view, final int resId) {
        view.setTag(resId);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (resId == 0) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = mThumbCache.get(resId);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        mThumbExecutor.execute(() -> {
            final Bitmap bitmap = decodeSampled(resId, mWallpaperPreviewSize);
            if (bitmap == null) {
                return;
            }
            mThumbCache.put(resId, bitmap);
            mMainHandler.post(() -> {
                // The holder may have been recycled for another item meanwhile.
                Object tag = view.getTag();
                if (tag instanceof Integer && (Integer) tag == resId) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap decodeSampled(int resId, int reqSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeResource(mRes, resId, bounds);
            int sample = 1;
            int shorter = Math.min(bounds.outWidth, bounds.outHeight);
            while (shorter > 0 && shorter / (sample * 2) >= reqSize) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inScaled = false;
            return BitmapFactory.decodeResource(mRes, resId, opts);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Failed to decode thumbnail " + resId, e);
            return null;
        }
    }

    private void sortWallpapers() {
        Collections.sort(mWallpaperList);
        mAdapter.notifyDataSetChanged();
    }
}
