/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.DecryptSeedLiveData
import de.schildbach.wallet.ui.preference.PinRetryController
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Inject

/**
 * @author:  Eric Britten
 */

@HiltViewModel
class DecryptSeedViewModel @Inject constructor(
    walletData: WalletDataProvider,
    pinRetryController: PinRetryController,
    walletApplication: WalletApplication,
    configuration: Configuration
) : CheckPinViewModel(walletData, configuration, pinRetryController) {

    internal val decryptSeedLiveData = DecryptSeedLiveData(walletApplication)

    override fun checkPin(pin: CharSequence) {
        decryptSeedLiveData.checkPin(pin.toString())
    }
}
