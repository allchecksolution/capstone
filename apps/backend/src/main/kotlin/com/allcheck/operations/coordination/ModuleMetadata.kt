package com.allcheck.operations.coordination

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** 세 업무 모듈의 공개 계약을 조합한다. 공유 계약을 소유하는 부모 모듈은 하위 구현을 참조하지 않는다. */
@ApplicationModule(displayName = "Operations Coordination")
@PackageInfo
internal class ModuleMetadata
