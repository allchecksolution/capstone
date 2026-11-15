package com.allcheck.reception.presentation

import com.allcheck.reception.presentation.api.ReceptionRecordingApi
import org.springframework.web.bind.annotation.RestController

/** 녹음 API의 HTTP 응답 처리를 담당할 컨트롤러 골격. */
@RestController
class ReceptionRecordingController : ReceptionRecordingApi
