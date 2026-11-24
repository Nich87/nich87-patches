package app.voidhack.extension.applemusic.lyrics;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * Headless Fragment hosting SAF file and folder pickers for local LRC loading.
 */
@SuppressWarnings("deprecation")
public final class LrcPickerFragment extends Fragment {
    private static final String TAG = "VoidhackLrcPicker";
    private static final String LOG_TAG = "AppleMusicLrclib";
    private static final int REQ_PICK_FILE = 0x564C;
    private static final int REQ_PICK_FOLDER = 0x564D;

    interface FileCallback {
        void onFilePicked(String fileName, LocalLrcParser.DecodedText decoded);
    }

    interface FolderCallback {
        void onFolderPicked(Uri treeUri, String displayLabel);
    }

    private FileCallback pendingFileCallback;
    private FolderCallback pendingFolderCallback;

    private static LrcPickerFragment obtain(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        LrcPickerFragment frag = (LrcPickerFragment) activity.getFragmentManager().findFragmentByTag(TAG);
        if (frag == null) {
            frag = new LrcPickerFragment();
            try {
                activity.getFragmentManager().beginTransaction().add(frag, TAG).commit();
                activity.getFragmentManager().executePendingTransactions();
            } catch (IllegalStateException e) {
                activity.getFragmentManager().beginTransaction().add(frag, TAG).commitAllowingStateLoss();
                activity.getFragmentManager().executePendingTransactions();
            }
        }
        return frag;
    }

    static void pickFile(Activity activity, FileCallback callback) {
        LrcPickerFragment frag = obtain(activity);
        if (frag == null) return;
        frag.pendingFileCallback = callback;
        frag.pendingFolderCallback = null;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            frag.startActivityForResult(intent, REQ_PICK_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, LrclibStrings.text(activity, "local_picker_unavailable"), Toast.LENGTH_SHORT).show();
        }
    }

    static void pickFolder(Activity activity, FolderCallback callback) {
        LrcPickerFragment frag = obtain(activity);
        if (frag == null) return;
        frag.pendingFolderCallback = callback;
        frag.pendingFileCallback = null;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            frag.startActivityForResult(intent, REQ_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, LrclibStrings.text(activity, "local_picker_unavailable"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Activity act = getActivity();
        if (requestCode == REQ_PICK_FILE) {
            FileCallback cb = pendingFileCallback;
            pendingFileCallback = null;
            if (act == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            handlePickedFile(act, data.getData(), cb);
        } else if (requestCode == REQ_PICK_FOLDER) {
            FolderCallback cb = pendingFolderCallback;
            pendingFolderCallback = null;
            if (act == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            handlePickedFolder(act, data, cb);
        }
    }

    private void handlePickedFile(Activity act, Uri uri, FileCallback cb) {
        try (InputStream in = act.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(act, LrclibStrings.text(act, "local_read_error"), Toast.LENGTH_LONG).show();
                return;
            }
            byte[] bytes = LocalLrcFolder.readLimitedBytes(in, LocalLrcParser.MAX_LRC_BYTES + 1);
            if (bytes == null || bytes.length == 0) {
                Toast.makeText(act, LrclibStrings.text(act, "local_invalid_file"), Toast.LENGTH_LONG).show();
                return;
            }
            if (bytes.length > LocalLrcParser.MAX_LRC_BYTES) {
                Toast.makeText(act, LrclibStrings.text(act, "local_file_too_large"), Toast.LENGTH_LONG).show();
                return;
            }
            LocalLrcParser.DecodedText decoded = LocalLrcParser.decodeBytes(bytes);
            if (decoded == null) {
                Toast.makeText(act, LrclibStrings.text(act, "local_invalid_file"), Toast.LENGTH_LONG).show();
                return;
            }
            LrclibParser.PreparedRecord check = LocalLrcParser.prepareLocalLrc(decoded.text, 0, true, false);
            if (check.parsed == null || check.parsed.lines.isEmpty()) {
                Toast.makeText(act, LrclibStrings.text(act, "local_invalid_file"), Toast.LENGTH_LONG).show();
                return;
            }
            String fileName = queryFileName(act, uri);
            if (cb != null) cb.onFilePicked(fileName, decoded);
        } catch (IOException | RuntimeException e) {
            Log.w(LOG_TAG, "Failed reading picked local LRC file", e);
            Toast.makeText(act, LrclibStrings.text(act, "local_read_error"), Toast.LENGTH_LONG).show();
        }
    }

    private void handlePickedFolder(Activity act, Intent data, FolderCallback cb) {
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            act.getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException e) {
            Log.w(LOG_TAG, "Could not persist URI permission for local LRC folder", e);
        }
        String label = LocalLrcFolder.describeTreeUri(treeUri);
        if (cb != null) cb.onFolderPicked(treeUri, label);
    }

    private static String queryFileName(Activity act, Uri uri) {
        try (Cursor c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        if (last != null) {
            int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf(':'));
            if (slash >= 0 && slash + 1 < last.length()) {
                return last.substring(slash + 1);
            }
            return last;
        }
        return "lyrics.lrc";
    }
}
