package com.example.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.RelayRepository
import com.example.domain.PhoneFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WebhookTemplate(val label: String, val sample: String) {
    TWILIO_FORM(
        label = "Twilio Form",
        sample = "To=%2B61485841378&From=%2B42777&Body=Your+Telegram+code+is+184920",
    ),
    TELNYX_JSON(
        label = "Telnyx JSON",
        sample = """{"data":{"payload":{"to":[{"phone_number":"+61485841378"}],"from":{"phone_number":"+14155550199"},"text":"Your Signal verification code is 629-410"}}}""",
    ),
}

data class ConnectUiState(
    val numberCount: Int = 0,
    val poolCap: Int = RelayRepository.POOL_CAP,
    val e164: String = "+61",
    val label: String = "",
    val countryCode: String = "AU",
    val carrier: String = "",
    val isSubmitting: Boolean = false,
    val formError: String? = null,
    val webhookTemplate: WebhookTemplate = WebhookTemplate.TWILIO_FORM,
    val webhookPayload: String = WebhookTemplate.TWILIO_FORM.sample,
    val isTestingWebhook: Boolean = false,
    val webhookError: String? = null,
    val webhookSuccessNumberId: String? = null,
    val webhookSuccessSummary: String? = null,
)

private data class ConnectFormState(
    val e164: String = "+61",
    val label: String = "",
    val countryCode: String = "AU",
    val carrier: String = "",
    val isSubmitting: Boolean = false,
    val formError: String? = null,
    val webhookTemplate: WebhookTemplate = WebhookTemplate.TWILIO_FORM,
    val webhookPayload: String = WebhookTemplate.TWILIO_FORM.sample,
    val isTestingWebhook: Boolean = false,
    val webhookError: String? = null,
    val webhookSuccessNumberId: String? = null,
    val webhookSuccessSummary: String? = null,
)

class ConnectViewModel(
    private val repository: RelayRepository,
) : ViewModel() {

    private val formState = MutableStateFlow(ConnectFormState())

    val uiState: StateFlow<ConnectUiState> = combine(
        repository.activeNumberCountFlow,
        formState,
    ) { count, form ->
        ConnectUiState(
            numberCount = count,
            poolCap = RelayRepository.POOL_CAP,
            e164 = form.e164,
            label = form.label,
            countryCode = form.countryCode,
            carrier = form.carrier,
            isSubmitting = form.isSubmitting,
            formError = form.formError,
            webhookTemplate = form.webhookTemplate,
            webhookPayload = form.webhookPayload,
            isTestingWebhook = form.isTestingWebhook,
            webhookError = form.webhookError,
            webhookSuccessNumberId = form.webhookSuccessNumberId,
            webhookSuccessSummary = form.webhookSuccessSummary,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ConnectUiState(),
    )

    fun onE164Changed(value: String) {
        formState.update { it.copy(e164 = value.take(20), formError = null) }
    }

    fun onLabelChanged(value: String) {
        formState.update { it.copy(label = value.take(40), formError = null) }
    }

    fun onCountryChanged(code: String) {
        val nextCountry = PhoneFormatter.SUPPORTED_COUNTRIES.firstOrNull { it.code == code } ?: return
        formState.update { current ->
            val currentPrefixes = PhoneFormatter.SUPPORTED_COUNTRIES.map { it.dialPrefix }.toSet()
            val updatedE164 = if (current.e164.trim().isEmpty() || current.e164.trim() in currentPrefixes) {
                nextCountry.dialPrefix
            } else {
                current.e164
            }
            current.copy(
                countryCode = nextCountry.code,
                e164 = updatedE164,
                formError = null,
            )
        }
    }

    fun onCarrierChanged(value: String) {
        formState.update { it.copy(carrier = value.take(40), formError = null) }
    }

    fun onSubmitLine(onAdded: (String) -> Unit) {
        val state = formState.value
        if (state.isSubmitting) return
        viewModelScope.launch {
            formState.update { it.copy(isSubmitting = true, formError = null) }
            val result = repository.registerNumber(
                e164Input = state.e164,
                labelInput = state.label,
                countryCodeInput = state.countryCode,
                carrierInput = state.carrier,
            )
            result.onSuccess { added ->
                formState.update {
                    it.copy(
                        e164 = PhoneFormatter.SUPPORTED_COUNTRIES
                            .firstOrNull { c -> c.code == it.countryCode }?.dialPrefix ?: "+61",
                        label = "",
                        carrier = "",
                        isSubmitting = false,
                        formError = null,
                    )
                }
                onAdded(added.id)
            }.onFailure { err ->
                formState.update {
                    it.copy(
                        isSubmitting = false,
                        formError = err.message ?: "Could not add that number",
                    )
                }
            }
        }
    }

    fun onSelectWebhookTemplate(template: WebhookTemplate) {
        formState.update {
            it.copy(
                webhookTemplate = template,
                webhookPayload = template.sample,
                webhookError = null,
                webhookSuccessNumberId = null,
                webhookSuccessSummary = null,
            )
        }
    }

    fun onWebhookPayloadChanged(value: String) {
        formState.update {
            it.copy(
                webhookPayload = value.take(1_200),
                webhookError = null,
                webhookSuccessSummary = null,
            )
        }
    }

    fun onDispatchWebhookPayload() {
        val state = formState.value
        if (state.isTestingWebhook || state.webhookPayload.isBlank()) return
        viewModelScope.launch {
            formState.update {
                it.copy(
                    isTestingWebhook = true,
                    webhookError = null,
                    webhookSuccessNumberId = null,
                    webhookSuccessSummary = null,
                )
            }
            val result = repository.acceptInboundWebhookPayload(state.webhookPayload)
            result.onSuccess { msg ->
                val otpNote = msg.extractedOtp?.let { " · OTP $it detected" }.orEmpty()
                formState.update {
                    it.copy(
                        isTestingWebhook = false,
                        webhookError = null,
                        webhookSuccessNumberId = msg.numberId,
                        webhookSuccessSummary = "Delivered to +${msg.numberId}$otpNote",
                    )
                }
            }.onFailure { err ->
                formState.update {
                    it.copy(
                        isTestingWebhook = false,
                        webhookError = err.message ?: "Payload rejected",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: RelayRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ConnectViewModel(repository) as T
                }
            }
    }
}
