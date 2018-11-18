package qr.code

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView

import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.vision.barcode.Barcode

import androidx.appcompat.app.AppCompatActivity

//主活动演示如何将额外参数传递给读取条形码的活动
class MainActivity : AppCompatActivity(), View.OnClickListener {
    //使用复合按钮,以便复选框或切换小部件工作
    private var autoFocus: CompoundButton? = null
    private var useFlash: CompoundButton? = null
    private var statusMessage: TextView? = null
    private var barcodeValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusMessage = findViewById(R.id.status_message)
        barcodeValue = findViewById(R.id.barcode_value)
        autoFocus = findViewById(R.id.auto_focus)
        useFlash = findViewById(R.id.use_flash)
        findViewById<View>(R.id.read_barcode).setOnClickListener(this)
    }

    override fun onClick(v: View) { //单击视图时调用
        if (v.id == R.id.read_barcode) { //启动条形码活动
            val intent = Intent(this, BarcodeCaptureActivity::class.java)
            intent.putExtra(BarcodeCaptureActivity.AutoFocus, autoFocus!!.isChecked)
            intent.putExtra(BarcodeCaptureActivity.UseFlash, useFlash!!.isChecked)
            startActivityForResult(intent, RC_BARCODE_CAPTURE)
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_BARCODE_CAPTURE) {
            if (resultCode == CommonStatusCodes.SUCCESS) {
                if (data != null) {
                    val barcode = data.getParcelableExtra<Barcode>(BarcodeCaptureActivity.BarcodeObject)
                    statusMessage!!.setText(R.string.barcode_success)
                    barcodeValue!!.text = barcode.displayValue
                    Log.d(TAG, "Barcode read: " + barcode.displayValue)
                } else {
                    statusMessage!!.setText(R.string.barcode_failure)
                    Log.d(TAG, "No barcode captured, intent data is null")
                }
            } else {
                statusMessage!!.text = String.format(getString(R.string.barcode_error),
                        CommonStatusCodes.getStatusCodeString(resultCode))
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        private val RC_BARCODE_CAPTURE = 9001
        private val TAG = "BarcodeMain"
    }
}
