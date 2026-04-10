package cn.classfun.droidvm.ui.vm.display.base;

import android.view.KeyEvent;

@SuppressWarnings("SpellCheckingInspection")
public final class X11Keymap {
    public static final int XK_BackSpace =                        0xff08;  /* U+0008 BACKSPACE */
    public static final int XK_Tab =                              0xff09;  /* U+0009 CHARACTER TABULATION */
    public static final int XK_Clear =                            0xff0b;  /* U+000B LINE TABULATION */
    public static final int XK_Return =                           0xff0d;  /* U+000D CARRIAGE RETURN */
    public static final int XK_Scroll_Lock =                      0xff14;
    public static final int XK_Sys_Req =                          0xff15;
    public static final int XK_Escape =                           0xff1b;  /* U+001B ESCAPE */
    public static final int XK_Delete =                           0xffff;  /* U+007F DELETE */
    public static final int XK_Home =                             0xff50;
    public static final int XK_Left =                             0xff51;  /* Move left, left arrow */
    public static final int XK_Up =                               0xff52;  /* Move up, up arrow */
    public static final int XK_Right =                            0xff53;  /* Move right, right arrow */
    public static final int XK_Down =                             0xff54;  /* Move down, down arrow */
    public static final int XK_Page_Up =                          0xff55;  /* deprecated alias for Prior */
    public static final int XK_Page_Down =                        0xff56;  /* deprecated alias for Next */
    public static final int XK_End =                              0xff57;  /* EOL */
    public static final int XK_Print =                            0xff61;
    public static final int XK_Insert =                           0xff63;  /* Insert, insert here */
    public static final int XK_Menu =                             0xff67;
    public static final int XK_Help =                             0xff6a;  /* Help */
    public static final int XK_Break =                            0xff6b;
    public static final int XK_Num_Lock =                         0xff7f;
    public static final int XK_KP_Enter =                         0xff8d;  /*<U+000D CARRIAGE RETURN>*/
    public static final int XK_KP_Equal =                         0xffbd;  /*<U+003D EQUALS SIGN>*/
    public static final int XK_KP_Multiply =                      0xffaa;  /*<U+002A ASTERISK>*/
    public static final int XK_KP_Add =                           0xffab;  /*<U+002B PLUS SIGN>*/
    public static final int XK_KP_Separator =                     0xffac;  /*<U+002C COMMA>*/
    public static final int XK_KP_Subtract =                      0xffad;  /*<U+002D HYPHEN-MINUS>*/
    public static final int XK_KP_Decimal =                       0xffae;  /*<U+002E FULL STOP>*/
    public static final int XK_KP_Divide =                        0xffaf;  /*<U+002F SOLIDUS>*/
    public static final int XK_KP_0 =                             0xffb0;  /*<U+0030 DIGIT ZERO>*/
    public static final int XK_F1 =                               0xffbe;
    public static final int XK_F13 =                              0xffca;
    public static final int XK_Shift_L =                          0xffe1;  /* Left shift */
    public static final int XK_Shift_R =                          0xffe2;  /* Right shift */
    public static final int XK_Control_L =                        0xffe3;  /* Left control */
    public static final int XK_Control_R =                        0xffe4;  /* Right control */
    public static final int XK_Caps_Lock =                        0xffe5;  /* Caps lock */
    public static final int XK_Meta_L =                           0xffe7;  /* Left meta */
    public static final int XK_Meta_R =                           0xffe8;  /* Right meta */
    public static final int XK_Alt_L =                            0xffe9;  /* Left alt */
    public static final int XK_Alt_R =                            0xffea;  /* Right alt */
    public static final int XK_3270_PrintScreen =                 0xfd1d;
    public static final int XK_space =                            0x0020;  /* U+0020 SPACE */
    public static final int XK_exclam =                           0x0021;  /* U+0021 EXCLAMATION MARK */
    public static final int XK_quotedbl =                         0x0022;  /* U+0022 QUOTATION MARK */
    public static final int XK_numbersign =                       0x0023;  /* U+0023 NUMBER SIGN */
    public static final int XK_dollar =                           0x0024;  /* U+0024 DOLLAR SIGN */
    public static final int XK_percent =                          0x0025;  /* U+0025 PERCENT SIGN */
    public static final int XK_ampersand =                        0x0026;  /* U+0026 AMPERSAND */
    public static final int XK_apostrophe =                       0x0027;  /* U+0027 APOSTROPHE */
    public static final int XK_parenleft =                        0x0028;  /* U+0028 LEFT PARENTHESIS */
    public static final int XK_parenright =                       0x0029;  /* U+0029 RIGHT PARENTHESIS */
    public static final int XK_asterisk =                         0x002a;  /* U+002A ASTERISK */
    public static final int XK_plus =                             0x002b;  /* U+002B PLUS SIGN */
    public static final int XK_comma =                            0x002c;  /* U+002C COMMA */
    public static final int XK_minus =                            0x002d;  /* U+002D HYPHEN-MINUS */
    public static final int XK_period =                           0x002e;  /* U+002E FULL STOP */
    public static final int XK_slash =                            0x002f;  /* U+002F SOLIDUS */
    public static final int XK_0 =                                0x0030;  /* U+0030 DIGIT ZERO */
    public static final int XK_1 =                                0x0031;  /* U+0031 DIGIT ONE */
    public static final int XK_2 =                                0x0032;  /* U+0032 DIGIT TWO */
    public static final int XK_3 =                                0x0033;  /* U+0033 DIGIT THREE */
    public static final int XK_4 =                                0x0034;  /* U+0034 DIGIT FOUR */
    public static final int XK_5 =                                0x0035;  /* U+0035 DIGIT FIVE */
    public static final int XK_6 =                                0x0036;  /* U+0036 DIGIT SIX */
    public static final int XK_7 =                                0x0037;  /* U+0037 DIGIT SEVEN */
    public static final int XK_8 =                                0x0038;  /* U+0038 DIGIT EIGHT */
    public static final int XK_9 =                                0x0039;  /* U+0039 DIGIT NINE */
    public static final int XK_colon =                            0x003a;  /* U+003A COLON */
    public static final int XK_semicolon =                        0x003b;  /* U+003B SEMICOLON */
    public static final int XK_less =                             0x003c;  /* U+003C LESS-THAN SIGN */
    public static final int XK_equal =                            0x003d;  /* U+003D EQUALS SIGN */
    public static final int XK_greater =                          0x003e;  /* U+003E GREATER-THAN SIGN */
    public static final int XK_question =                         0x003f;  /* U+003F QUESTION MARK */
    public static final int XK_at =                               0x0040;  /* U+0040 COMMERCIAL AT */
    public static final int XK_bracketleft =                      0x005b;  /* U+005B LEFT SQUARE BRACKET */
    public static final int XK_backslash =                        0x005c;  /* U+005C REVERSE SOLIDUS */
    public static final int XK_bracketright =                     0x005d;  /* U+005D RIGHT SQUARE BRACKET */
    public static final int XK_asciicircum =                      0x005e;  /* U+005E CIRCUMFLEX ACCENT */
    public static final int XK_underscore =                       0x005f;  /* U+005F LOW LINE */
    public static final int XK_grave =                            0x0060;  /* U+0060 GRAVE ACCENT */
    public static final int XK_a =                                0x0061;  /* U+0061 LATIN SMALL LETTER A */
    public static final int XK_braceleft =                        0x007b;  /* U+007B LEFT CURLY BRACKET */
    public static final int XK_bar =                              0x007c;  /* U+007C VERTICAL LINE */
    public static final int XK_braceright =                       0x007d;  /* U+007D RIGHT CURLY BRACKET */
    public static final int XK_asciitilde =                       0x007e;  /* U+007E TILDE */
    public static final int XF86XK_MonBrightnessUp =          0x1008ff02;  /* Monitor/panel brightness */
    public static final int XF86XK_MonBrightnessDown =        0x1008ff03;  /* Monitor/panel brightness */
    public static final int XF86XK_KbdBrightnessUp =          0x1008ff05;  /* Keyboards may be lit     */
    public static final int XF86XK_KbdBrightnessDown =        0x1008ff06;  /* Keyboards may be lit     */
    public static final int XF86XK_AudioLowerVolume =         0x1008ff11;  /* Volume control down        */
    public static final int XF86XK_AudioMute =                0x1008ff12;  /* Mute sound from the system */
    public static final int XF86XK_AudioRaiseVolume =         0x1008ff13;  /* Volume control up          */
    public static final int XF86XK_AudioPlay =                0x1008ff14;  /* Start playing of audio >   */
    public static final int XF86XK_AudioStop =                0x1008ff15;  /* Stop playing audio         */
    public static final int XF86XK_AudioPrev =                0x1008ff16;  /* Previous track             */
    public static final int XF86XK_AudioNext =                0x1008ff17;  /* Next track                 */
    public static final int XF86XK_HomePage =                 0x1008ff18;  /* Display user's home page   */
    public static final int XF86XK_Mail =                     0x1008ff19;  /* Invoke user's mail program */
    public static final int XF86XK_Search =                   0x1008ff1b;  /* Search                     */
    public static final int XF86XK_AudioRecord =              0x1008ff1c;  /* Record audio application   */
    public static final int XF86XK_Calculator =               0x1008ff1d;  /* Invoke calculator program  */
    public static final int XF86XK_Calendar =                 0x1008ff20;  /* Invoke Calendar program    */
    public static final int XF86XK_Back =                     0x1008ff26;  /* Like back on a browser     */
    public static final int XF86XK_Forward =                  0x1008ff27;  /* Like forward on a browser  */
    public static final int XF86XK_Refresh =                  0x1008ff29;  /* Refresh the page           */
    public static final int XF86XK_PowerOff =                 0x1008ff2a;  /* Power off system entirely  */
    public static final int XF86XK_WakeUp =                   0x1008ff2b;  /* Wake up system from sleep  */
    public static final int XF86XK_Eject =                    0x1008ff2c;  /* Eject device (e.g. DVD)    */
    public static final int XF86XK_Sleep =                    0x1008ff2f;  /* Put system to sleep        */
    public static final int XF86XK_Favorites =                0x1008ff30;  /* Show favorite locations    */
    public static final int XF86XK_AudioPause =               0x1008ff31;  /* Pause audio playing        */
    public static final int XF86XK_AudioRewind =              0x1008ff3e;  /* "rewind" audio track        */
    public static final int XF86XK_Close =                    0x1008ff56;  /* Close window                */
    public static final int XF86XK_Copy =                     0x1008ff57;  /* Copy selection              */
    public static final int XF86XK_Cut =                      0x1008ff58;  /* Cut selection               */
    public static final int XF86XK_Explorer =                 0x1008ff5d;  /* Launch file explorer        */
    public static final int XF86XK_New =                      0x1008ff68;  /* New (folder, document...    */
    public static final int XF86XK_Paste =                    0x1008ff6d;  /* Paste                       */
    public static final int XF86XK_Phone =                    0x1008ff6e;  /* Launch phone; dial number   */
    public static final int XF86XK_ZoomIn =                   0x1008ff8b;  /* zoom in view, map, etc.   */
    public static final int XF86XK_ZoomOut =                  0x1008ff8c;  /* zoom out view, map, etc.  */
    public static final int XF86XK_WebCam =                   0x1008ff8f;  /* Launch web camera app.    */
    public static final int XF86XK_Music =                    0x1008ff92;  /* Launch music application  */
    public static final int XF86XK_AudioForward =             0x1008ff97;  /* fast-forward audio track    */
    public static final int XF86XK_AudioCycleTrack =          0x1008ff9b;  /* cycle through audio tracks  */
    public static final int XF86XK_Red =                      0x1008ffa3;  /* Red button                  */
    public static final int XF86XK_Green =                    0x1008ffa4;  /* Green button                */
    public static final int XF86XK_Yellow =                   0x1008ffa5;  /* Yellow button               */
    public static final int XF86XK_Blue =                     0x1008ffa6;  /* Blue button                 */
    public static final int XF86XK_Suspend =                  0x1008ffa7;  /* Sleep to RAM                */
    public static final int XF86XK_AudioMicMute =             0x1008ffb2;  /* Mute the Mic from the system */
    public static final int XF86XK_FullScreen =               0x1008ffb8;  /* Toggle fullscreen */
    public static final int XF86XK_Info =                     0x10081166;  /* v2.5.26 KEY_INFO */
    public static final int XF86XK_MediaSelectProgramGuide =  0x1008116a;  /* v2.5.26 KEY_PROGRAM */
    public static final int XF86XK_MediaLanguageMenu =        0x10081170;  /* v2.5.26 KEY_LANGUAGE */
    public static final int XF86XK_MediaSelectTV =            0x10081179;  /* v2.5.26 KEY_TV */
    public static final int XF86XK_MediaSelectAuxiliary =     0x10081186;  /* v2.5.26 KEY_AUX */
    public static final int XF86XK_ChannelUp =                0x10081192;  /* v2.5.26 KEY_CHANNELUP */
    public static final int XF86XK_ChannelDown =              0x10081193;  /* v2.5.26 KEY_CHANNELDOWN */
    public static final int XF86XK_HangupPhone =              0x100811be;  /* v5.10   KEY_HANGUP_PHONE */
    public static final int XF86XK_Fn =                       0x100811d0;  /*         KEY_FN */
    public static final int XF86XK_AppSelect =                0x10081244;  /* v3.16   KEY_APPSELECT */
    public static final int XF86XK_Assistant =                0x10081247;  /* v4.13   KEY_ASSISTANT */
    public static final int XF86XK_EmojiPicker =              0x10081249;  /* v5.13   KEY_EMOJI_PICKER */
    public static final int XF86XK_Dictate =                  0x1008124a;  /* v5.17   KEY_DICTATE */
    public static final int XF86XK_DoNotDisturb =             0x1008124f;  /* v6.10   KEY_DO_NOT_DISTURB */
    public static final int XF86XK_3DMode =                   0x1008126f;  /* v4.7    KEY_3D_MODE */
    public static final int XF86XK_Macro1 =                   0x10081290;  /* v5.5    KEY_MACRO1 */
    public static final int XF86XK_Macro2 =                   0x10081291;  /* v5.5    KEY_MACRO2 */
    public static final int XF86XK_Macro3 =                   0x10081292;  /* v5.5    KEY_MACRO3 */
    public static final int XF86XK_Macro4 =                   0x10081293;  /* v5.5    KEY_MACRO4 */
    private X11Keymap() {
    }

    public static int androidKeyToXKeysym(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NAVIGATE_IN:
                return XK_Return;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_NAVIGATE_OUT:
                return XK_Escape;
            case KeyEvent.KEYCODE_TAB:
                return XK_Tab;
            case KeyEvent.KEYCODE_DEL:
                return XK_BackSpace;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return XK_Delete;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_SOFT_LEFT:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                return XK_Left;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                return XK_Up;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_SOFT_RIGHT:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                return XK_Right;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                return XK_Down;
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_HOME:
                return XK_Home;
            case KeyEvent.KEYCODE_MOVE_END:
                return XK_End;
            case KeyEvent.KEYCODE_PAGE_UP:
                return XK_Page_Up;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return XK_Page_Down;
            case KeyEvent.KEYCODE_INSERT:
                return XK_Insert;
            case KeyEvent.KEYCODE_CLEAR:
                return XK_Clear;
            case KeyEvent.KEYCODE_MENU:
                return XK_Menu;
            case KeyEvent.KEYCODE_HELP:
                return XK_Help;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return XK_Shift_L;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return XK_Shift_R;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return XK_Control_L;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return XK_Control_R;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return XK_Alt_L;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return XK_Alt_R;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return XK_Caps_Lock;
            case KeyEvent.KEYCODE_META_LEFT:
                return XK_Meta_L;
            case KeyEvent.KEYCODE_META_RIGHT:
                return XK_Meta_R;
            case KeyEvent.KEYCODE_SCROLL_LOCK:
                return XK_Scroll_Lock;
            case KeyEvent.KEYCODE_NUM_LOCK:
                return XK_Num_Lock;
            case KeyEvent.KEYCODE_FUNCTION:
                return XF86XK_Fn;
            case KeyEvent.KEYCODE_SYSRQ:
                return XK_Sys_Req;
            case KeyEvent.KEYCODE_BREAK:
                return XK_Break;
            case KeyEvent.KEYCODE_SPACE:
                return XK_space;
            case KeyEvent.KEYCODE_GRAVE:
                return XK_grave;
            case KeyEvent.KEYCODE_MINUS:
                return XK_minus;
            case KeyEvent.KEYCODE_EQUALS:
                return XK_equal;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return XK_bracketleft;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return XK_bracketright;
            case KeyEvent.KEYCODE_BACKSLASH:
                return XK_backslash;
            case KeyEvent.KEYCODE_SEMICOLON:
                return XK_semicolon;
            case KeyEvent.KEYCODE_APOSTROPHE:
                return XK_apostrophe;
            case KeyEvent.KEYCODE_COMMA:
                return XK_comma;
            case KeyEvent.KEYCODE_PERIOD:
                return XK_period;
            case KeyEvent.KEYCODE_SLASH:
                return XK_slash;
            case KeyEvent.KEYCODE_AT:
                return XK_at;
            case KeyEvent.KEYCODE_STAR:
                return XK_asterisk;
            case KeyEvent.KEYCODE_POUND:
                return XK_numbersign;
            case KeyEvent.KEYCODE_PLUS:
                return XK_plus;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
                return XK_KP_Divide;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                return XK_KP_Multiply;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                return XK_KP_Subtract;
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                return XK_KP_Add;
            case KeyEvent.KEYCODE_NUMPAD_DOT:
                return XK_KP_Decimal;
            case KeyEvent.KEYCODE_NUMPAD_COMMA:
                return XK_KP_Separator;
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return XK_KP_Enter;
            case KeyEvent.KEYCODE_NUMPAD_EQUALS:
                return XK_KP_Equal;
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN:
                return XK_parenleft;
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN:
                return XK_parenright;
            case KeyEvent.KEYCODE_VOLUME_UP:
                return XF86XK_AudioRaiseVolume;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return XF86XK_AudioLowerVolume;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return XF86XK_AudioMute;
            case KeyEvent.KEYCODE_MUTE:
                return XF86XK_AudioMicMute;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return XF86XK_AudioPlay;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return XF86XK_AudioPause;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return XF86XK_AudioStop;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return XF86XK_AudioNext;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return XF86XK_AudioPrev;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return XF86XK_AudioRewind;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return XF86XK_AudioForward;
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                return XF86XK_AudioRecord;
            case KeyEvent.KEYCODE_MEDIA_EJECT:
                return XF86XK_Eject;
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_LOCK:
                return XF86XK_PowerOff;
            case KeyEvent.KEYCODE_SLEEP:
                return XF86XK_Sleep;
            case KeyEvent.KEYCODE_WAKEUP:
                return XF86XK_WakeUp;
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                return XF86XK_MonBrightnessDown;
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
                return XF86XK_MonBrightnessUp;
            case KeyEvent.KEYCODE_EXPLORER:
                return XF86XK_Explorer;
            case KeyEvent.KEYCODE_ENVELOPE:
                return XF86XK_Mail;
            case KeyEvent.KEYCODE_CALCULATOR:
                return XF86XK_Calculator;
            case KeyEvent.KEYCODE_MUSIC:
                return XF86XK_Music;
            case KeyEvent.KEYCODE_SEARCH:
                return XF86XK_Search;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                return XF86XK_Back;
            case KeyEvent.KEYCODE_FORWARD:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                return XF86XK_Forward;
            case KeyEvent.KEYCODE_REFRESH:
                return XF86XK_Refresh;
            case KeyEvent.KEYCODE_CUT:
                return XF86XK_Cut;
            case KeyEvent.KEYCODE_COPY:
                return XF86XK_Copy;
            case KeyEvent.KEYCODE_PASTE:
                return XF86XK_Paste;
            case KeyEvent.KEYCODE_CALL:
                return XF86XK_Phone;
            case KeyEvent.KEYCODE_ENDCALL:
                return XF86XK_HangupPhone;
            case KeyEvent.KEYCODE_CAMERA:
                return XF86XK_WebCam;
            case KeyEvent.KEYCODE_MEDIA_CLOSE:
            case KeyEvent.KEYCODE_CLOSE:
                return XF86XK_Close;
            case KeyEvent.KEYCODE_INFO:
                return XF86XK_Info;
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return XF86XK_ChannelUp;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return XF86XK_ChannelDown;
            case KeyEvent.KEYCODE_ZOOM_IN:
                return XF86XK_ZoomIn;
            case KeyEvent.KEYCODE_ZOOM_OUT:
                return XF86XK_ZoomOut;
            case KeyEvent.KEYCODE_TV:
                return XF86XK_MediaSelectTV;
            case KeyEvent.KEYCODE_WINDOW:
                return XF86XK_MediaSelectAuxiliary;
            case KeyEvent.KEYCODE_GUIDE:
                return XF86XK_MediaSelectProgramGuide;
            case KeyEvent.KEYCODE_BOOKMARK:
                return XF86XK_Favorites;
            case KeyEvent.KEYCODE_PROG_RED:
                return XF86XK_Red;
            case KeyEvent.KEYCODE_PROG_GREEN:
                return XF86XK_Green;
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return XF86XK_Yellow;
            case KeyEvent.KEYCODE_PROG_BLUE:
                return XF86XK_Blue;
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_RECENT_APPS:
                return XF86XK_AppSelect;
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH:
                return XF86XK_MediaLanguageMenu;
            case KeyEvent.KEYCODE_3D_MODE:
                return XF86XK_3DMode;
            case KeyEvent.KEYCODE_CALENDAR:
                return XF86XK_Calendar;
            case KeyEvent.KEYCODE_ASSIST:
                return XF86XK_Assistant;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                return XF86XK_AudioCycleTrack;
            case KeyEvent.KEYCODE_SOFT_SLEEP:
                return XF86XK_Suspend;
            case KeyEvent.KEYCODE_ALL_APPS:
                return XF86XK_HomePage;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN:
                return XF86XK_KbdBrightnessDown;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP:
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE:
                return XF86XK_KbdBrightnessUp;
            case KeyEvent.KEYCODE_MACRO_1:
                return XF86XK_Macro1;
            case KeyEvent.KEYCODE_MACRO_2:
                return XF86XK_Macro2;
            case KeyEvent.KEYCODE_MACRO_3:
                return XF86XK_Macro3;
            case KeyEvent.KEYCODE_MACRO_4:
                return XF86XK_Macro4;
            case KeyEvent.KEYCODE_EMOJI_PICKER:
                return XF86XK_EmojiPicker;
            case KeyEvent.KEYCODE_SCREENSHOT:
                return XK_3270_PrintScreen;
            case KeyEvent.KEYCODE_DICTATE:
                return XF86XK_Dictate;
            case KeyEvent.KEYCODE_NEW:
                return XF86XK_New;
            case KeyEvent.KEYCODE_DO_NOT_DISTURB:
                return XF86XK_DoNotDisturb;
            case KeyEvent.KEYCODE_PRINT:
                return XK_Print;
            case KeyEvent.KEYCODE_FULLSCREEN:
                return XF86XK_FullScreen;
            default:
                if (keyCode >= 326 /*KeyEvent.KEYCODE_F13*/ && keyCode <= 337 /*KeyEvent.KEYCODE_F24*/)
                    return XK_F13 + (keyCode - 326 /*KeyEvent.KEYCODE_F13*/);
                if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12)
                    return XK_F1 + (keyCode - KeyEvent.KEYCODE_F1);
                if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
                    return XK_a + (keyCode - KeyEvent.KEYCODE_A);
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
                    return XK_0 + (keyCode - KeyEvent.KEYCODE_0);
                if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9)
                    return XK_KP_0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
                return 0;
        }
    }
}
