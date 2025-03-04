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
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.backup.RestoreFromFileActivity
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_onboarding.*
import kotlinx.android.synthetic.main.activity_onboarding_perm_lock.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.util.getMainTask
import org.slf4j.LoggerFactory
import javax.inject.Inject

private const val REGULAR_FLOW_TUTORIAL_REQUEST_CODE = 0
const val SET_PIN_REQUEST_CODE = 1
private const val RESTORE_PHRASE_REQUEST_CODE = 2
private const val RESTORE_FILE_REQUEST_CODE = 3
private const val UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE = 4

@FlowPreview
@AndroidEntryPoint
@ExperimentalCoroutinesApi
class OnboardingActivity : RestoreFromFileActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(OnboardingActivity::class.java)
        private const val EXTRA_UPGRADE = "upgrade"
        @JvmStatic
        @JvmOverloads
        fun createIntent(context: Context, upgrade: Boolean = false): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_UPGRADE, upgrade)
            }
        }

        // this function removes the previous task which may still be running after an
        // app upgrade
        private fun removePreviousTask(activity: AppCompatActivity) {
            log.info("do we need to remove the previous task")
            val mainTask = activity.getMainTask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (mainTask.taskInfo.taskId != activity.taskId) {
                    log.info("removing previous task")
                    mainTask.finishAndRemoveTask()
                }
            } else {
                // when installing over 6.6.6 or lower, topActivity is null
                if (mainTask.taskInfo.topActivity?.className != this::class.java.name) {
                    log.info("removing previous task < Android Q")
                    mainTask.finishAndRemoveTask()
                }
            }
        }
    }

    private lateinit var viewModel: OnboardingViewModel

    private lateinit var walletApplication: WalletApplication

    @Inject
    lateinit var analytics: AnalyticsService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletApplication = (application as WalletApplication)

        if (PinRetryController.getInstance().isLockedForever) {
            setContentView(R.layout.activity_onboarding_perm_lock)
            getStatusBarHeightPx()
            hideSlogan()
            close_app.setOnClickListener {
                finish()
            }
            wipe_wallet.setOnClickListener {
                ResetWalletDialog.newInstance().show(supportFragmentManager, "reset_wallet_dialog")
            }
            return
        }

        setContentView(R.layout.activity_onboarding)
        slogan.setPadding(slogan.paddingLeft, slogan.paddingTop, slogan.paddingRight, getStatusBarHeightPx())

        viewModel = ViewModelProvider(this)[OnboardingViewModel::class.java]

        // TODO: we should decouple the logic from view interactions
        // and move some of this to the viewModel, wrapping it in tests.
        // The viewModel already has some related events
        if (walletApplication.walletFileExists()) {
            if (!walletApplication.wallet!!.isEncrypted) {
                unencryptedFlow()
            } else {
                if (walletApplication.isWalletUpgradedToBIP44) {
                    regularFlow()
                } else {
                    upgradeToBIP44Flow()
                }
            }
        } else {
            if (walletApplication.wallet == null) {
                onboarding()
            } else {
                if (walletApplication.wallet!!.isEncrypted) {
                    walletApplication.fullInitialization()
                    regularFlow()
                } else {
                    onboarding()
                }
            }
        }
        // during an upgrade, for some reason the previous screen is still in the recent app list
        // this will find it and close it
        if (intent.extras?.getBoolean(EXTRA_UPGRADE) == true) {
            removePreviousTask(this)
        }
    }

    // This is due to a wallet being created in an invalid way
    // such that the wallet is not encrypted
    private fun unencryptedFlow() {
        log.info("the wallet is not encrypted -- the wallet will be upgraded")
        if (walletApplication.configuration.v7TutorialCompleted) {
            upgradeUnencryptedWallet()
        } else {
            startActivityForResult(Intent(this, WelcomeActivity::class.java),
                UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun upgradeUnencryptedWallet() {
        viewModel.finishUnecryptedWalletUpgradeAction.observe(this) {
            startActivityForResult(SetPinActivity.createIntent(application, R.string.set_pin_upgrade_wallet, upgradingWallet = true), SET_PIN_REQUEST_CODE)
        }
        viewModel.upgradeUnencryptedWallet()
    }

    private fun upgradeToBIP44Flow() {
        // for now do nothing extra, it will be handled in WalletActivity
        regularFlow()
    }

    private fun regularFlow() {
        if (walletApplication.configuration.v7TutorialCompleted) {
            upgradeOrStartMainActivity()
        } else {
            startActivityForResult(Intent(this, WelcomeActivity::class.java),
                    REGULAR_FLOW_TUTORIAL_REQUEST_CODE)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun upgradeOrStartMainActivity() {
        if (SecurityGuard.isConfiguredQuickCheck()) {
            startMainActivity()
        } else {
            startActivity(AppUpgradeActivity.createIntent(this))
        }
    }

    private fun startMainActivity() {
        startActivity(WalletActivity.createIntent(this))
        finish()
    }

    private fun onboarding() {
        initView()
        initViewModel()
        showButtonsDelayed()
        if (!walletApplication.configuration.v7TutorialCompleted) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun initView() {
        create_new_wallet.setOnClickListener {
            viewModel.createNewWallet()
        }
        recovery_wallet.setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            startActivityForResult(Intent(this, RestoreWalletFromSeedActivity::class.java), REQUEST_CODE_RESTORE_WALLET)
        }
        restore_wallet.setOnClickListener {
            restoreWalletFromFile()
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showToastAction.observe(this, Observer {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        })
        viewModel.showRestoreWalletFailureAction.observe(this, Observer {
            val message = when {
                TextUtils.isEmpty(it.message) -> it.javaClass.simpleName
                else -> it.message!!
            }

            BaseAlertDialogBuilder(this).apply {
                title = getString(R.string.import_export_keys_dialog_failure_title)
                this.message = getString(R.string.import_keys_dialog_failure, message)
                positiveText = getString(R.string.button_dismiss)
                negativeText = getString(R.string.button_retry)
                negativeAction = {
                    RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
                }
                showIcon = true
            }.buildAlertDialog().show()
        })
        viewModel.finishCreateNewWalletAction.observe(this) {
            startActivityForResult(SetPinActivity.createIntent(application, R.string.set_pin_create_new_wallet), SET_PIN_REQUEST_CODE)
        }
    }

    private fun showButtonsDelayed() {
        Handler().postDelayed({
            hideSlogan()
            findViewById<LinearLayout>(R.id.buttons).visibility = View.VISIBLE
        }, 1000)
    }

    private fun hideSlogan() {
        val sloganDrawable = (window.decorView.background as LayerDrawable).getDrawable(1)
        sloganDrawable.mutate().alpha = 0
    }

    private fun getStatusBarHeightPx(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REGULAR_FLOW_TUTORIAL_REQUEST_CODE) {
            upgradeOrStartMainActivity()
        } else if (requestCode == UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE) {
            upgradeUnencryptedWallet()
        } else if ((requestCode == SET_PIN_REQUEST_CODE || requestCode == RESTORE_PHRASE_REQUEST_CODE) && resultCode == Activity.RESULT_OK) {
            finish()
        }
    }
}
