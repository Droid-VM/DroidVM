package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import android.widget.TextView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.size.SizeUtils;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.lib.store.enums.EnumPicker;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class TextInputRowWidget extends FrameLayout {
    private static final int MODE_NORMAL = 0;
    private static final int MODE_NUMBER = 1;
    private static final int MODE_SIZE = 2;

    private final Context context;
    private ImageView iconView;
    private TextInputLayout textInputLayout;
    private TextInputEditText editText;
    private PickerButtonWidget buttonView;
    private MaterialButton iconButtonView;
    private int mode = MODE_NORMAL;
    private BigInteger minValue = BigInteger.ZERO;
    private BigInteger maxValue = BigInteger.valueOf(Long.MAX_VALUE);
    private BigInteger precision = BigInteger.ONE;
    private final AtomicBoolean updatingValue = new AtomicBoolean(false);
    private Runnable onFocusLostListener;

    public TextInputRowWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public TextInputRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public TextInputRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_text_input_row, this, true);
        iconView = findViewById(R.id.ti_icon);
        textInputLayout = findViewById(R.id.ti_layout);
        editText = findViewById(R.id.ti_edit);
        buttonView = findViewById(R.id.ti_button);
        iconButtonView = findViewById(R.id.ti_icon_button);
        initAttrs(attrs);
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.TextInputRowWidget)) {
            var icon = a.getDrawable(R.styleable.TextInputRowWidget_android_icon);
            if (icon != null) {
                iconView.setImageDrawable(icon);
            } else {
                iconView.setVisibility(GONE);
                var lp = (TextInputLayout.LayoutParams) textInputLayout.getLayoutParams();
                lp.setMarginStart(0);
                textInputLayout.setLayoutParams(lp);
            }
            var hint = a.getString(R.styleable.TextInputRowWidget_android_hint);
            if (hint != null) {
                textInputLayout.setHint(hint);
                iconView.setContentDescription(hint);
            }
            var inputType = a.getInt(
                R.styleable.TextInputRowWidget_android_inputType,
                InputType.TYPE_NULL
            );
            if (inputType != InputType.TYPE_NULL)
                editText.setInputType(inputType);
            var lines = a.getInt(R.styleable.TextInputRowWidget_ti_maxLines, 1);
            editText.setMaxLines(lines);
            var endIconDrawable = a.getDrawable(R.styleable.TextInputRowWidget_ti_endIcon);
            if (endIconDrawable != null) {
                var endIconMode = a.getInt(
                    R.styleable.TextInputRowWidget_ti_endIconMode,
                    TextInputLayout.END_ICON_CUSTOM
                );
                textInputLayout.setEndIconMode(endIconMode);
                textInputLayout.setEndIconDrawable(endIconDrawable);
            }
            var buttonText = a.getBoolean(R.styleable.TextInputRowWidget_ti_textButton, false);
            buttonView.setVisibility(buttonText ? VISIBLE : GONE);
            var iconButtonIcon = a.getDrawable(R.styleable.TextInputRowWidget_ti_iconButtonIcon);
            if (iconButtonIcon != null) {
                iconButtonView.setIcon(iconButtonIcon);
                iconButtonView.setVisibility(VISIBLE);
                var desc = a.getString(R.styleable.TextInputRowWidget_ti_iconButtonHint);
                if (desc != null)
                    iconButtonView.setContentDescription(desc);
            } else {
                iconButtonView.setVisibility(GONE);
            }
            var tiMode = a.getInt(R.styleable.TextInputRowWidget_ti_mode, MODE_NORMAL);
            var min = a.getString(R.styleable.TextInputRowWidget_ti_min);
            var max = a.getString(R.styleable.TextInputRowWidget_ti_max);
            if (!isInEditMode()) {
                applyMode(tiMode, min, max);
            } else if (tiMode == MODE_SIZE)
                buttonView.setVisibility(VISIBLE);
            var precision = a.getString(R.styleable.TextInputRowWidget_ti_precision);
            if (precision != null) {
                var parsed = SizeUtils.parseBigSize(precision);
                if (parsed.compareTo(BigInteger.ZERO) <= 0)
                    throw new IllegalArgumentException("Precision must be positive");
                this.precision = parsed;
            }
        }
    }

    private void setPickerByMinMax(
        @Nullable String min,
        @Nullable String max
    ) {
        var minUnit = SizeUnit.MIN;
        var maxUnit = SizeUnit.MAX;
        if (min != null) {
            var parsed = SizeUtils.parseBigSize(min);
            if (parsed.compareTo(BigInteger.ZERO) < 0)
                throw new IllegalArgumentException("Min value must be non-negative");
            var pair = SizeUtils.findUnit(parsed);
            minValue = parsed;
            minUnit = pair.getUnit();
        }
        if (max != null) {
            var parsed = SizeUtils.parseBigSize(max);
            if (parsed.compareTo(BigInteger.ZERO) < 0)
                throw new IllegalArgumentException("Min value must be non-negative");
            var pair = SizeUtils.findUnit(parsed);
            maxValue = parsed;
            maxUnit = pair.getUnit();
        }
        if (minValue.compareTo(maxValue) > 0)
            throw new IllegalArgumentException("Min value cannot be greater than max value");
        if (minUnit.ordinal() >= maxUnit.ordinal())
            throw new IllegalArgumentException("Invalid min/max values");
        buttonView.setItems(minUnit, maxUnit);
    }

    private void applyMode(int mode, @Nullable String min, @Nullable String max) {
        this.mode = mode;
        if (mode == MODE_NUMBER) {
            var flags = InputType.TYPE_CLASS_NUMBER;
            editText.setInputType(flags);
            if (min != null)
                this.minValue = new BigInteger(min);
            if (max != null)
                this.maxValue = new BigInteger(max);
            if (this.minValue.compareTo(this.maxValue) > 0)
                throw new IllegalArgumentException("Min value cannot be greater than max value");
            if (this.minValue.compareTo(BigInteger.ZERO) < 0)
                flags |= InputType.TYPE_NUMBER_FLAG_SIGNED;
            editText.setInputType(flags);
        } else if (mode == MODE_SIZE) {
            var flags = InputType.TYPE_CLASS_NUMBER;
            flags |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
            buttonView.setVisibility(VISIBLE);
            if (!isInEditMode()) {
                buttonView.setMode(PickerButtonWidget.Mode.ROTATE);
                setPickerByMinMax(min, max);
            }
            editText.setInputType(flags);
        }
        if (mode == MODE_NUMBER || mode == MODE_SIZE)
            setupListeners();
    }

    private void setupListeners() {
        editText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validateInput();
            }
        });
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyPrecision();
                if (onFocusLostListener != null) onFocusLostListener.run();
            }
        });
        if (mode == MODE_SIZE)
            buttonView.setOnValueChangedListener(this::onUnitChanged);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isInputValid() {
        if (mode != MODE_NUMBER && mode != MODE_SIZE) return true;
        var text = getText();
        if (text.isEmpty()) return false;
        try {
            var number = new BigDecimal(text);
            BigInteger bytes;
            if (mode == MODE_SIZE) {
                SizeUnit unit = buttonView.getSelectedItem();
                bytes = number.multiply(new BigDecimal(unit.getBigFactor())).toBigInteger();
            } else {
                bytes = number.toBigInteger();
            }
            return bytes.compareTo(minValue) >= 0 && bytes.compareTo(maxValue) <= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void validateInput() {
        if (updatingValue.get()) return;
        if (mode != MODE_NUMBER && mode != MODE_SIZE) return;
        var text = getText();
        if (text.isEmpty()) {
            textInputLayout.setError(null);
            return;
        }
        if (!isInputValid()) {
            textInputLayout.setError(context.getString(
                R.string.text_input_item_range,
                mode == MODE_SIZE ? SizeUtils.formatSize(minValue) : minValue.toString(),
                mode == MODE_SIZE ? SizeUtils.formatSize(maxValue) : maxValue.toString()
            ));
        } else {
            textInputLayout.setError(null);
        }
    }

    private void onUnitChanged(Enum<?> oldVal, Enum<?> newVal) {
        if (updatingValue.get()) return;
        if (!(oldVal instanceof SizeUnit) || !(newVal instanceof SizeUnit)) return;
        var oldUnit = (SizeUnit) oldVal;
        var newUnit = (SizeUnit) newVal;
        if (oldUnit == newUnit) return;
        var text = getText();
        if (text.isEmpty()) return;
        try {
            var number = new BigDecimal(text);
            var bytes = number.multiply(new BigDecimal(oldUnit.getBigFactor()));
            var converted = bytes.divide(
                new BigDecimal(newUnit.getBigFactor()),
                MathContext.UNLIMITED
            );
            updatingValue.set(true);
            try {
                setTextAndMoveCursor(converted
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
                );
            } finally {
                updatingValue.set(false);
            }
        } catch (Exception ignored) {
        }
    }

    private BigInteger roundToPrecision(BigInteger value) {
        if (precision.equals(BigInteger.ONE)) return value;
        var divRem = value.divideAndRemainder(precision);
        if (divRem[1].equals(BigInteger.ZERO)) return value;
        if (divRem[1].shiftLeft(1).compareTo(precision) >= 0)
            return divRem[0].add(BigInteger.ONE).multiply(precision);
        return divRem[0].multiply(precision);
    }

    private void applyPrecision() {
        if (updatingValue.get()) return;
        if (mode != MODE_NUMBER && mode != MODE_SIZE) return;
        if (precision.equals(BigInteger.ONE)) return;
        var text = getText();
        if (text.isEmpty()) return;
        try {
            var value = getBigValue();
            var rounded = roundToPrecision(value);
            if (!rounded.equals(value)) setBigValue(rounded);
        } catch (Exception ignored) {
        }
        validateInput();
    }

    public void setSelection(int index) {
        editText.setSelection(index);
    }

    public void setError(@Nullable CharSequence error) {
        textInputLayout.setError(error);
    }

    public void setError(@StringRes int resId) {
        textInputLayout.setError(context.getString(resId));
    }

    @NonNull
    public String getText() {
        var e = editText.getText();
        return e != null ? e.toString() : "";
    }

    public void setText(@Nullable CharSequence text) {
        editText.setText(text);
    }

    public void setEndIconOnClickListener(
        @Nullable OnClickListener listener
    ) {
        textInputLayout.setEndIconOnClickListener(listener);
    }

    public void addTextChangedListener(@NonNull TextWatcher watcher) {
        editText.addTextChangedListener(watcher);
    }

    public void setOnFocusLostListener(@Nullable Runnable listener) {
        this.onFocusLostListener = listener;
    }

    public void setOnEditorActionListener(
        @Nullable TextView.OnEditorActionListener listener
    ) {
        editText.setOnEditorActionListener(listener);
    }

    @SuppressWarnings("unused")
    public void setIconButtonOnClickListener(@Nullable OnClickListener listener) {
        iconButtonView.setOnClickListener(listener);
    }

    @SuppressWarnings("unused")
    public void setIconButtonOnClickListener(@Nullable Runnable listener) {
        iconButtonView.setOnClickListener(listener == null ? null : v -> listener.run());
    }

    public void setTextAndMoveCursor(@NonNull String text) {
        try {
            editText.setText(text);
            if (editText.getText() != null)
                editText.setSelection(editText.getText().length());
        } catch (Exception ignored) {
        }
    }

    public BigInteger getBigValue() {
        if (mode != MODE_NUMBER && mode != MODE_SIZE)
            throw new IllegalStateException("Unsupported mode");
        var text = getText();
        if (text.isEmpty()) return BigInteger.ZERO;
        var value = new BigDecimal(text);
        if (mode == MODE_SIZE) {
            SizeUnit unit = buttonView.getSelectedItem();
            value = value.multiply(new BigDecimal(unit.getBigFactor()));
        }
        return value.toBigInteger();
    }

    public long getValue() {
        var bs = getBigValue();
        if (bs.bitLength() >= Long.SIZE)
            throw new NumberFormatException("Number too large");
        return bs.longValueExact();
    }

    public long getValue(@NonNull SizeUnit unit) {
        var bs = getBigValue();
        var converted = new BigDecimal(bs).divide(
            new BigDecimal(unit.getBigFactor()), MathContext.UNLIMITED
        );
        return converted.toBigInteger().longValueExact();
    }

    public void setBigValue(BigInteger value) {
        if (mode != MODE_NUMBER && mode != MODE_SIZE)
            throw new IllegalStateException("Unsupported mode");
        updatingValue.set(true);
        try {
            if (value.compareTo(minValue) < 0)
                value = minValue;
            if (value.compareTo(maxValue) > 0)
                value = maxValue;
            value = roundToPrecision(value);
            if (value.compareTo(minValue) < 0)
                value = minValue;
            if (value.compareTo(maxValue) > 0)
                value = maxValue;
            if (mode == MODE_SIZE) {
                EnumPicker<SizeUnit> picker = buttonView.getPicker();
                var units = picker.getItems();
                var pair = SizeUtils.findFloatUnit(value, units);
                setTextAndMoveCursor(pair.getNumberFloat());
                buttonView.setSelectedItem(pair.getUnit());
            }
            if (mode == MODE_NUMBER)
                setTextAndMoveCursor(String.valueOf(value));
        } finally {
            updatingValue.set(false);
        }
    }

    public void setValue(long value) {
        setBigValue(BigInteger.valueOf(value));
    }

    public void setValue(long value, SizeUnit unit) {
        if (mode != MODE_SIZE)
            throw new IllegalStateException("Unsupported mode");
        var bigValue = BigInteger.valueOf(value).multiply(unit.getBigFactor());
        setBigValue(bigValue);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        editText.setEnabled(enabled);
        textInputLayout.setEnabled(enabled);
        buttonView.setEnabled(enabled);
        iconButtonView.setEnabled(enabled);
    }
}
