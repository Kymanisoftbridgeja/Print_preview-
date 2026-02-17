package com.receiptbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.repository.PrinterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrinterViewModel @Inject constructor(
    private val repository: PrinterRepository
) : ViewModel() {

    val profiles = repository.allProfiles

    fun addProfile(name: String, type: ConnectionType, address: String) {
        viewModelScope.launch {
            val profile = PrinterProfile(
                name = name,
                connectionType = type,
                address = address
            )
            repository.saveProfile(profile)
        }
    }

    fun deleteProfile(profile: PrinterProfile) {
        viewModelScope.launch {
            repository.deleteProfile(profile)
        }
    }
    
    fun setDefault(profile: PrinterProfile) {
        viewModelScope.launch {
             repository.saveProfile(profile.copy(isDefault = true))
        }
    }
}
