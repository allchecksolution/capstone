package com.allcheck.operations.work

import com.allcheck.global.storage.SignedObjectUrl

/** 업무 권한 확인은 조정 계층에서, 파일 형식·S3 정책은 work 내부에서 적용한다. */
interface PhotoStorageOperations {
    fun bucket(): String
    fun available()
    fun upload(photo: PhotoSnapshot): SignedObjectUrl
    fun access(photo: PhotoSnapshot): SignedObjectUrl
    fun verify(photo: PhotoSnapshot)
}
