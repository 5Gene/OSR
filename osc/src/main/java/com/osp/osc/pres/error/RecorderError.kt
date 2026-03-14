package com.osp.osc.pres.error

sealed class RecorderError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class EncoderError(message: String, cause: Throwable? = null) : RecorderError(message, cause)

    class MuxerError(message: String, cause: Throwable? = null) : RecorderError(message, cause)

    class DisplayError(message: String, cause: Throwable? = null) : RecorderError(message, cause)

    class PresentationError(message: String, cause: Throwable? = null) : RecorderError(message, cause)

    class ConfigurationError(message: String, cause: Throwable? = null) : RecorderError(message, cause)

    class IllegalStateError(message: String, cause: Throwable? = null) : RecorderError(message, cause)
}
