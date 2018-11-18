package qr.code

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.vision.barcode.Barcode
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*

//主活动演示如何将额外参数传递给读取条形码的活动
class MainActivity : AppCompatActivity() {
    //使用复合按钮,以便复选框或切换小部件工作

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) //启动条形码活动
        read_barcode.setOnClickListener {
            //单击视图时调用
            val intent = Intent(this, BarcodeCaptureActivity::class.java)
            intent.putExtra(BarcodeCaptureActivity.UseFlash, use_flash!!.isChecked)
            startActivityForResult(intent, RC_BARCODE_CAPTURE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_BARCODE_CAPTURE) {
            if (resultCode == CommonStatusCodes.SUCCESS) {
                if (data != null) {
                    val barcode = data.getParcelableExtra<Barcode>(BarcodeCaptureActivity.BarcodeObject)
                    status_message!!.setText(R.string.barcode_success)
                    barcode_value!!.text = barcode.displayValue
                } else {
                    status_message!!.setText(R.string.barcode_failure)
                    Snackbar.make(use_flash, "未捕获条形码,意图数据为空", 1000).show()
                }
            } else {
                status_message!!.text = String.format(getString(R.string.barcode_error),
                        CommonStatusCodes.getStatusCodeString(resultCode))
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        private const val RC_BARCODE_CAPTURE = 9001
    }
}
