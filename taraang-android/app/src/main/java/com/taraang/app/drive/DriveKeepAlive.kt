package com.taraang.app.drive

/**
 * 주행 중 프로세스를 살려 두는 장치(포그라운드 서비스). 차량 내비가 앞에 오면 앱은 배경이 되고, 실차에서는
 * 캐시 앱 동결·메모리 회수로 주행 루프가 멈출 수 있다. 서비스는 프로세스만 붙잡고 루프는 컨트롤러에 그대로 둔다.
 * 실패해도 주행은 막지 않는다(구현체가 예외를 삼키고 기록한다).
 */
interface DriveKeepAlive {
    /** 주행이 RUNNING이 되면 부른다. */
    fun start()

    /** 주행이 FINISHED/FAILED가 되면 부른다. 여러 번 불러도 된다. */
    fun stop()

    companion object {
        val None = object : DriveKeepAlive {
            override fun start() = Unit
            override fun stop() = Unit
        }
    }
}
