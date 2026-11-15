package com.allcheck.reception.presentation

import com.allcheck.reception.presentation.api.ReceptionApi
import org.springframework.web.bind.annotation.RestController

/** 접수 API의 HTTP 응답 처리를 담당할 컨트롤러 골격. */
@RestController
class ReceptionController : ReceptionApi
