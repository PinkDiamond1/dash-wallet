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

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject

@SuppressLint("Registered")
@AndroidEntryPoint
@ExperimentalCoroutinesApi
@FlowPreview
open class ShortcutComponentActivity : AppCompatActivity() {
    @Inject lateinit var walletApplication: WalletApplication


    open fun finishIfNotInitialized(): Boolean {
        if (walletApplication.wallet == null) {
            startActivity(OnboardingActivity.createIntent(this))
            finish()
            return true
        }
        return false
    }
}
