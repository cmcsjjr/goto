package com.rk.file

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.rk.DefaultScope
import com.rk.activities.main.ui.fileTreeViewModel
import com.rk.resources.getString
import com.rk.utils.application
import com.rk.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.io.Util.copyStream

var to_save_file: FileObject? = null

class FileManager(private val activity: ComponentActivity) {

    private fun getString(@StringRes id: Int): String = id.getString()

    private val activityResultLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            activityResultCallback?.invoke(result)
            activityResultCallback = null
        }
    private var activityResultCallback: ((ActivityResult) -> Unit)? = null

    private val directoryPickerLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            directoryPickerCallback?.invoke(uri)
            directoryPickerCallback = null
        }
    private var directoryPickerCallback: ((Uri?) -> Unit)? = null

    private fun launchActivityForResult(intent: Intent, callback: (ActivityResult) -> Unit) {
        activityResultCallback = callback
        activityResultLauncher.launch(intent)
    }

    private fun launchDirectoryPicker(callback: (Uri?) -> Unit) {
        directoryPickerCallback = callback
        directoryPickerLauncher.launch(null)
    }

    /**
     * Helper to resolve whether to use SAF (ACTION_OPEN_DOCUMENT) 
     * or fallback chooser (ACTION_GET_CONTENT) for File Manager+.
     */
    private fun getBestPickerIntent(context: Context, mimeType: String): Intent {
        val fileManagerPlusPackage = "com.alphainventor.filemanager"
        val isFileManagerInstalled = try {
            context.packageManager.getPackageInfo(fileManagerPlusPackage, 0)
            true
        } catch (e: Exception) {
            false
        }
    
        return if (isFileManagerInstalled) {
            // Broad intent that forces Android's bottom app chooser dialog
            val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // Use wildcard so File Manager+ isn't filtered out
            }
            
            // DO NOT attach ACTION_OPEN_DOCUMENT to EXTRA_INITIAL_INTENTS
            Intent.createChooser(getContentIntent, "Select File with")
        } else {
            // Fallback to SAF if File Manager+ isn't installed
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
            }
        }
    }
    fun requestOpenFile(mimeType: String = "*/*", callback: (Uri?) -> Unit) {
        val intent = getBestPickerIntent(activity, mimeType)
        launchActivityForResult(intent) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                uri?.let { safeTakePersistableUriPermission(it) }
                callback(uri)
            } else {
                callback(null)
            }
        }
    }

    fun requestOpenDirectory(callback: (Uri?) -> Unit) {
	    val fileManagerPlusPackage = "com.alphainventor.filemanager"
	    val isFileManagerInstalled = try {
	        activity.packageManager.getPackageInfo(fileManagerPlusPackage, 0)
	        true
	    } catch (e: Exception) {
	        false
	    }

	    if (isFileManagerInstalled) {
	        // Broad folder picker intent that allows File Manager+ to handle the request
	        val folderIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
	            addCategory(Intent.CATEGORY_OPENABLE)
	            type = "resource/folder"
	        }
	        
	        // SAF directory picker as fallback option
	        val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

	        val chooserIntent = Intent.createChooser(folderIntent, "Select Directory").apply {
	            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(treeIntent))
	        }

	        launchActivityForResult(chooserIntent) { result ->
	            if (result.resultCode == Activity.RESULT_OK) {
	                val uri = result.data?.data
	                uri?.let { safeTakePersistableUriPermission(it) }
	                callback(uri)
	            } else {
	                callback(null)
	            }
	        }
	    } else {
	        // Standard SAF tree picker
	        launchDirectoryPicker { uri ->
	            uri?.let { safeTakePersistableUriPermission(it) }
	            callback(uri)
	        }
	    }
	}
    var parentFile: FileObject? = null

    fun requestAddFile(parent: FileObject, callback: (FileObject?) -> Unit = {}) {
        parentFile = parent
        val intent = getBestPickerIntent(activity, "*/*")

        launchActivityForResult(intent) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                callback(null)
                parentFile = null
                return@launchActivityForResult
            }

            val sourceUri = result.data?.data
                ?: run {
                    callback(null)
                    parentFile = null
                    return@launchActivityForResult
                }

            // Standard GET_CONTENT URIs won't yield persistable permissions,
            // but we call this safely anyway just in case it is a SAF URI.
            safeTakePersistableUriPermission(sourceUri)

            DefaultScope.launch(Dispatchers.IO) {
                try {
                    val fileName = getFileName(activity.contentResolver, sourceUri)
                    val destinationFile = parentFile?.createChild(true, fileName)

                    destinationFile?.let { file ->
                        copyUriData(activity.contentResolver, sourceUri, file.toUri())
                        withContext(Dispatchers.Main) {
                            fileTreeViewModel.get()?.updateCache(parentFile!!)
                            callback(file)
                        }
                    } ?: run { withContext(Dispatchers.Main) { callback(null) } }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { callback(null) }
                } finally {
                    parentFile = null
                }
            }
        }
    }

    /**
     * Safely attempts to take persistable URI permissions without throwing 
     * a SecurityException when non-SAF targets (like File Manager+) are used.
     */
    private fun safeTakePersistableUriPermission(uri: Uri) {
        runCatching {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            activity.contentResolver.takePersistableUriPermission(uri, takeFlags)
        }.onFailure { e ->
            // Non-SAF apps (e.g. File Manager+) return standard content URIs that don't support persistable flags.
            // Catching SecurityException/IllegalArgumentException here prevents crashes.
            e.printStackTrace()
        }
    }

    fun selectDirForNewFileLaunch(fileName: String, callback: (FileObject?) -> Unit = {}) {
        launchDirectoryPicker { uri ->
            if (uri == null) {
                callback(null)
                return@launchDirectoryPicker
            }

            try {
                this.activity.lifecycleScope.launch(Dispatchers.IO) {
                    val fileObject = uri.toFileObject(expectedIsFile = true)
                    if (fileObject.hasChild(fileName)) {
                        toast("File with name $fileName already exists")
                        callback(null)
                    } else {
                        val newFile = fileObject.createChild(true, fileName)
                        callback(newFile)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }
    }

    fun createNewFile(mimeType: String, title: String, callback: (FileObject?) -> Unit = {}) {
        launchActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, title)
            }
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                this.activity.lifecycleScope.launch {
                    val uri = result.data?.data
                    val fileObject = uri?.toFileObject(expectedIsFile = true)
                    callback(fileObject)
                }
            } else {
                callback(null)
            }
        }
    }

    fun requestOpenDirectoryToSaveFile(file: FileObject, callback: (Boolean) -> Unit = {}) {
        to_save_file = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        val activities = application!!.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        if (activities.isNotEmpty()) {
            launchActivityForResult(intent) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = result.data?.data
                    if (uri != null) {
                        DefaultScope.launch(Dispatchers.IO) {
                            try {
                                activity.contentResolver.openInputStream(file.toUri()).use { inputStream ->
                                    activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                        inputStream?.copyTo(outputStream)
                                        callback(true)
                                    } ?: callback(false)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                callback(false)
                            }
                        }
                    } else {
                        callback(false)
                    }
                } else {
                    callback(false)
                }
            }
        } else {
            launchDirectoryPicker { uri ->
                if (uri == null) {
                    callback(false)
                    return@launchDirectoryPicker
                }

                DefaultScope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openInputStream(file.toUri()).use { inputStream ->
                            activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                inputStream?.copyTo(outputStream)
                                callback(true)
                            } ?: callback(false)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        callback(false)
                    }
                }
            }
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        var name = "default_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun copyUriData(contentResolver: ContentResolver, sourceUri: Uri, destinationUri: Uri) {
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                copyStream(inputStream, outputStream)
            }
        } ?: throw RuntimeException("Failed to copy data from $sourceUri to $destinationUri")
    }
}
