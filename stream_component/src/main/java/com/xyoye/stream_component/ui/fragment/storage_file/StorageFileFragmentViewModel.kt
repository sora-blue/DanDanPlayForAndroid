package com.xyoye.stream_component.ui.fragment.storage_file

import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.FileComparator
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.stream_component.utils.storage.StorageSortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StorageFileFragmentViewModel : BaseViewModel() {
    companion object {
        private val lastPlayDirectory = PlayHistoryEntity(
            url = "",
            mediaType = MediaType.OTHER_STORAGE,
            videoName = "",
            isLastPlay = true
        )
    }

    lateinit var storage: Storage
    private val hidePointFile = AppConfig.isShowHiddenFile().not()
    private var sortOption = StorageSortOption()

    private val _fileLiveData = MutableLiveData<List<StorageFile>>()
    val fileLiveData: LiveData<List<StorageFile>> = _fileLiveData

    //当前媒体库中最后一次播放记录
    private var storageLastPlay: PlayHistoryEntity? = null

    fun listFile(directory: StorageFile?, refresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = directory ?: storage.getRootFile()
            if (target == null) {
                _fileLiveData.postValue(emptyList())
                return@launch
            }

            refreshStorageLastPlay()
            val childFiles = storage.openDirectory(target, refresh)
                .filter {
                    isDisplayFile(it)
                }.sortedWith(
                    FileComparator({ it.fileName() }, { it.isDirectory() })
                ).onEach {
                    it.playHistory = getHistory(it)
                }
            _fileLiveData.postValue(childFiles)
        }
    }

    fun changeSortOption(option: StorageSortOption) {
        sortOption = option
    }

    fun searchByText(word: String) {

    }

    fun unbindExtraSource(file: StorageFile, unbindDanmu: Boolean) {
        viewModelScope.launch {
            if (unbindDanmu) {
                DatabaseManager.instance.getPlayHistoryDao().updateDanmu(
                    file.uniqueKey(), storage.library.mediaType, null, 0
                )
            } else {
                DatabaseManager.instance.getPlayHistoryDao().updateSubtitle(
                    file.uniqueKey(), storage.library.mediaType, null
                )
            }
            updateHistory()
        }
    }

    fun updateHistory() {
        viewModelScope.launch {
            refreshStorageLastPlay()

            val fileList = _fileLiveData.value ?: return@launch
            val newFileList = fileList.map {
                val history = getHistory(it)
                val isSameHistory = if (it.isFile()) {
                    it.playHistory == history
                } else {
                    it.playHistory?.id == history?.id
                }
                if (isSameHistory) {
                    return@map it
                }
                //历史记录不一致时，返回拷贝的新对象
                it.clone().apply { playHistory = history }
            }
            _fileLiveData.postValue(newFileList)
        }
    }

    private suspend fun getHistory(file: StorageFile): PlayHistoryEntity? {
        if (file.isDirectory()) {
            return lastPlayDirectoryHistory(file)
        }
        if (file.isVideoFile().not()) {
            return null
        }
        var history = DatabaseManager.instance
            .getPlayHistoryDao()
            .getPlayHistory(file.uniqueKey(), file.storage.library.id)
        if (history == null) {
            //这是一步补救措施，数据库11版本之前，没有存储storageId字段
            //因此为了避免弹幕等历史数据无法展示，依旧需要通过mediaType查询
            history = DatabaseManager.instance
                .getPlayHistoryDao()
                .getPlayHistory(file.uniqueKey(), file.storage.library.mediaType)
        }
        if (history != null && storageLastPlay != null) {
            history.isLastPlay = history.id == storageLastPlay!!.id
        }
        return history
    }

    /**
     * 刷新最后一次播放记录
     */
    private suspend fun refreshStorageLastPlay() {
        storageLastPlay = DatabaseManager.instance
            .getPlayHistoryDao()
            .gitStorageLastPlay(storage.library.id)
        storageLastPlay?.isLastPlay = true
    }

    /**
     * 文件夹是否为最后一次播放记录的父文件夹
     * 是：返回最后播放的标签
     * 否：null
     */
    private fun lastPlayDirectoryHistory(file: StorageFile): PlayHistoryEntity? {
        val lastPlayStoragePath = storageLastPlay?.storagePath
            ?: return null
        if (TextUtils.isEmpty(lastPlayStoragePath)) {
            return null
        }
        if (file.isStoragePathParent(lastPlayStoragePath).not()) {
            return null
        }
        return lastPlayDirectory
    }

    /**
     * 是否可展示的文件
     */
    private fun isDisplayFile(storageFile: StorageFile): Boolean {
        //.开头的文件，根据配置展示
        if (hidePointFile && storageFile.fileName().startsWith(".")) {
            return false
        }
        //文件夹，展示
        if (storageFile.isDirectory()) {
            return true
        }
        //视频文件，展示
        return storageFile.isVideoFile()
    }
}