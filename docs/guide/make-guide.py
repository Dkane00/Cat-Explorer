from reportlab.lib.pagesizes import letter
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.platypus import (BaseDocTemplate, PageTemplate, Frame, Paragraph, Spacer, Image, Table, TableStyle,
                                PageBreak, KeepTogether, NextPageTemplate, CondPageBreak)
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from PIL import Image as PILImage

import os
HERE = os.path.dirname(os.path.abspath(__file__))
S = os.path.join(HERE, 'shots') + '/'
OUT = os.path.join(HERE, '..', 'Cat-Explorer-User-Guide.pdf')
FD = '/usr/share/fonts/truetype/liberation/'
pdfmetrics.registerFont(TTFont('Body', FD + 'LiberationSans-Regular.ttf'))
pdfmetrics.registerFont(TTFont('Body-Bold', FD + 'LiberationSans-Bold.ttf'))
pdfmetrics.registerFont(TTFont('Body-Italic', FD + 'LiberationSans-Italic.ttf'))
pdfmetrics.registerFont(TTFont('Body-BoldItalic', FD + 'LiberationSans-BoldItalic.ttf'))
pdfmetrics.registerFont(TTFont('Mono', FD + 'LiberationMono-Regular.ttf'))
pdfmetrics.registerFontFamily('Body', normal='Body', bold='Body-Bold', italic='Body-Italic', boldItalic='Body-BoldItalic')

BLUE = colors.HexColor('#0B6FA4')
DARK = colors.HexColor('#15202B')
GREY = colors.HexColor('#5A6672')
LIGHT = colors.HexColor('#EEF3F7')
AMBER = colors.HexColor('#FFF4E0')

body = ParagraphStyle('body', fontName='Body', fontSize=10.5, leading=14.5, textColor=DARK, spaceAfter=6)
small = ParagraphStyle('small', parent=body, fontSize=9, leading=12, textColor=GREY)
cap = ParagraphStyle('cap', parent=body, fontSize=9, leading=12, textColor=GREY, alignment=TA_CENTER, spaceAfter=10)
h1 = ParagraphStyle('h1', fontName='Body-Bold', fontSize=20, leading=24, textColor=BLUE, spaceBefore=4, spaceAfter=10, keepWithNext=1)
h1x = ParagraphStyle('h1x', parent=h1)
h2 = ParagraphStyle('h2', fontName='Body-Bold', fontSize=13.5, leading=17, textColor=DARK, spaceBefore=10, spaceAfter=4, keepWithNext=1)
bullet = ParagraphStyle('bullet', parent=body, leftIndent=16, bulletIndent=4, spaceAfter=3)
cell = ParagraphStyle('cell', parent=body, fontSize=9.5, leading=12.5, spaceAfter=0)
cellb = ParagraphStyle('cellb', parent=cell, fontName='Body-Bold')
title = ParagraphStyle('title', fontName='Body-Bold', fontSize=34, leading=40, textColor=BLUE, alignment=TA_CENTER)
subtitle = ParagraphStyle('subtitle', fontName='Body', fontSize=15, leading=20, textColor=GREY, alignment=TA_CENTER)

W = 7.0 * inch


def P(t, st=body): return Paragraph(t, st)


def sec(t, need=3.0):
    return [CondPageBreak(need * inch), Paragraph(t, h1)]


BIG = ('Bands tab', 'CW tab (FLRig connection)', 'CW panel', 'FM panel', 'VOICE panel', 'Memory mode')


def H2(t):
    need = 4.6 if (t in BIG[:5] or t == 'Controls') else 2.6
    return [CondPageBreak(need * inch), Paragraph(t, h2)]


def B(items):
    return [Paragraph(t, bullet, bulletText='\u2022') for t in items]


def img(name, width=W):
    im = PILImage.open(S + name + '.png')
    w, h = im.size
    return Image(S + name + '.png', width=width, height=width * h / w)


def shot(name, caption, width=W):
    return KeepTogether([img(name, width), P(caption, cap)])


def box(text, kind='Note'):
    bg = LIGHT if kind != 'Careful' else AMBER
    t = Table([[Paragraph('<b>%s.</b> %s' % (kind, text), cell)]], colWidths=[W])
    t.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), bg), ('BOX', (0, 0), (-1, -1), 0.6, GREY),
                           ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
                           ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6)]))
    return [t, Spacer(1, 8)]


def table(rows, widths, header=True):
    data = []
    for i, r in enumerate(rows):
        data.append([Paragraph(str(c), cellb if (header and i == 0) else cell) for c in r])
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    st = [('GRID', (0, 0), (-1, -1), 0.4, colors.HexColor('#B8C2CC')), ('VALIGN', (0, 0), (-1, -1), 'TOP'),
          ('LEFTPADDING', (0, 0), (-1, -1), 6), ('RIGHTPADDING', (0, 0), (-1, -1), 6),
          ('TOPPADDING', (0, 0), (-1, -1), 4), ('BOTTOMPADDING', (0, 0), (-1, -1), 4)]
    if header: st.append(('BACKGROUND', (0, 0), (-1, 0), LIGHT))
    t.setStyle(TableStyle(st))
    return [KeepTogether([t]) if len(rows) <= 12 else t, Spacer(1, 8)]


class Doc(BaseDocTemplate):
    def afterFlowable(self, f):
        if isinstance(f, Paragraph) and f.style.name == 'h1':
            self.notify('TOCEntry', (0, f.getPlainText(), self.page))


def footer(canv, doc):
    canv.saveState()
    canv.setFont('Body', 8.5); canv.setFillColor(GREY)
    canv.drawString(0.75 * inch, 0.5 * inch, 'Cat Explorer User Guide')
    canv.drawRightString(letter[0] - 0.75 * inch, 0.5 * inch, 'Page %d' % doc.page)
    canv.restoreState()


doc = Doc(OUT, pagesize=letter, leftMargin=0.75 * inch, rightMargin=0.75 * inch, topMargin=0.7 * inch, bottomMargin=0.8 * inch,
          title='Cat Explorer User Guide', author='Cat Explorer')
frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id='f')
doc.addPageTemplates([PageTemplate(id='first', frames=[frame]), PageTemplate(id='rest', frames=[frame], onPage=footer)])

s = []
# ---------------------------------------------------------------- cover
s += [Spacer(1, 1.2 * inch), P('Cat Explorer', title), Spacer(1, 6), P('User Guide', subtitle), Spacer(1, 4),
      P('A touchscreen controller for the Yaesu FT-891', subtitle), Spacer(1, 2), P('Version 1.2.0', subtitle), Spacer(1, 0.4 * inch), img('02-main', W), Spacer(1, 0.4 * inch)]
s += box('Cat Explorer is built for the <b>Yaesu FT-891</b> only. Its controls, meter scales, filter widths and memory '
         'channels are all written for that radio, so it will not work properly with any other model.')
s += [NextPageTemplate('rest'), PageBreak()]

toc = TableOfContents()
toc.levelStyles = [ParagraphStyle('t0', fontName='Body', fontSize=11.5, leading=19, leftIndent=0, textColor=DARK)]
s += [P('Contents', h1x), toc, PageBreak()]

# ---------------------------------------------------------------- 1 welcome
s += [P('1. Welcome', h1),
      P('Cat Explorer lets you see and control your FT-891 from an Android tablet or phone. It shows the frequency, mode, filter '
        'width and meters live, and gives you touch controls for the radio\'s everyday functions: tuning, bands, memories, DSP and '
        'filter settings, mic equalizer, CW, FM and voice messages, and an antenna-tuning button. It can also show a '
        'waterfall of the band, with band edges and license privileges marked, and tune to a signal when you tap it: through FLRig, or '
        'with an SDR plugged straight into the tablet.'),
      P('You can connect in either of two ways:'),
      ] + B(['<b>Directly over USB.</b> Plug the radio into the tablet with a USB-C OTG cable. No computer is needed.',
             '<b>Through FLRig.</b> Run FLRig on the computer that the radio is plugged into and connect the tablet to it over your '
             'network (Wi-Fi or VPN).']) + [Spacer(1, 4),
      P('Everything in this guide works with both connections unless it says otherwise. Section 16 lists the few differences.'),
      *H2('What you need')] + B([
          'A Yaesu FT-891.',
          'An Android tablet or phone with Cat Explorer installed. For USB mode the device must support USB host (OTG).',
          'For USB mode: a USB-C OTG cable (or a USB-C adapter plus the radio\'s USB cable) that carries data, not just power.',
          'For FLRig mode: a computer running FLRig with its XML-RPC server turned on, on the same network as the tablet.']) + [
      *H2('A note on transmitting')] + box(
          'Some buttons make the radio <b>transmit</b>: Tune, the CW keyer memory buttons and playing a voice message. Check that '
          'your antenna is connected and use low power when you try a new feature.', 'Careful')

# ---------------------------------------------------------------- 2 connecting
s += [*sec('2. Connecting to the radio', 4.6),
      shot('01-connect', 'The connect screen. The top half is for FLRig, the bottom half for a direct USB connection.'),
      *H2('Connect over USB')] + B([
          'On the radio, open the menu and set <b>05-06 CAT RATE</b> to the speed you want to use (for example 9600). Remember the value.',
          'Switch the radio on and plug its USB port into the tablet with the OTG cable.',
          'In Cat Explorer, type the same speed into the <b>Baud rate</b> box.',
          'Tap <b>Connect over USB</b>. The first time, Android asks whether Cat Explorer may use the USB device. Tap <b>OK</b>.',
          'The main screen appears and the status card at the bottom shows a green dot, <b>RX FT-891</b> and <b>USB CAT</b>.']) + [
      Spacer(1, 4)] + box('The baud rate in the app must match the radio\'s CAT RATE. If the radio does not answer, check the two '
                          'numbers first, then check that the radio is on and the cable carries data.') + [
      *H2('Connect through FLRig')] + B([
          'On the computer, open FLRig and make sure it is connected to the radio.',
          'In FLRig turn on the XML-RPC server (Config, Setup, UI, Server) and allow its port (12345 by default) through the '
          'computer\'s firewall. FLRig must listen on the network, not only on the local computer.',
          'In Cat Explorer, type the computer\'s address into <b>FLRig PC address</b> and the port into <b>XML-RPC port</b>.',
          'Tap <b>Connect</b>. The status card shows <b>RX FT-891</b> and the FLRig version.']) + [
      *H2('Disconnecting'),
      P('Tap <b>Disconnect</b> on the status card to return to the connect screen. The app remembers the address, port and baud rate.'),
      *H2('One radio, one connection'),
      P('A radio can talk to only one computer at a time. While the tablet is connected over USB, a PC cannot use the same USB port '
        'for FLRig or other software. Use FLRig mode if you want the computer to keep control of the radio.')]

# ---------------------------------------------------------------- 3 main screen tour
s += [*sec('3. The main screen', 4.6),
      shot('02-main-annotated', 'The main screen. The numbers match the list below.'),
      ] + table([
          ['', 'What it is', 'See'],
          ['1', 'Large frequency display for the active VFO. Tap it to type a frequency.', 'Section 4'],
          ['2', 'The other VFO\'s frequency. Tap it to change that VFO without switching to it.', 'Section 4'],
          ['3', 'Band, Mode, filter width (BW) and Memories buttons.', 'Section 5'],
          ['4', 'The filter graph. Tap it to open the DSP &amp; IF screen.', 'Section 9'],
          ['5', 'The mic equalizer graph. Tap it to open the TX equalizer screen.', 'Section 10'],
          ['6', 'VFO buttons: A, B, VFO/MEM, A/B, A→B, Split and Lock.', 'Section 4'],
          ['7', 'Tune, IPO/AMP, ATT, and the CW, FM and VOICE panels.', 'Sections 6 and 11'],
          ['8', 'Signal history and Waterfall card. Tap the tab names at its top left to switch.', 'Sections 7 and 15'],
          ['9', 'Meters: S, PWR, SWR, ALC, COMP and ID.', 'Section 7'],
          ['10', 'Sliders: VOL, RF, MIC and PWR W (transmit power).', 'Section 8'],
          ['11', 'Status card: connection state, Settings, CAT console and Disconnect.', 'Sections 13 and 14'],
      ], [0.45 * inch, 5.15 * inch, 1.4 * inch]) + [
      P('With a FLRig connection, scroll down for a card of extra shortcuts (section 12).'),
      *H2('Tablets and phones'),
      Table([[P('On a wide screen (a tablet held sideways) the meters sit beside the sliders. On a narrow screen (a phone, or a tablet held '
                'upright) the same controls are stacked in one column, the filter and equalizer graphs move to the right of the frequency, and '
                'the Band, Mode and BW buttons go under the VFO buttons. The picture shows the tablet held upright.'),
              img('29-portrait-main', 2.5 * inch)]], colWidths=[4.2 * inch, 2.8 * inch],
            style=TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP')]))]

# ---------------------------------------------------------------- 4 frequency and VFOs
s += [*sec('4. Frequency and VFOs'),
      *H2('Changing the frequency'),
      P('Tap the large frequency to open the <b>Enter frequency</b> box. You can type the frequency in kHz or MHz. Add a letter to be '
        'sure: <b>k</b> for kHz or <b>m</b> for MHz.'),
      ] + table([
          ['You type', 'You get'],
          ['7.074', '7.074 MHz'],
          ['7074', '7.074 MHz (a number from 61 up to 99999 is read as kHz)'],
          ['14.2m', '14.200 MHz'],
          ['3573k', '3.573 MHz'],
          ['7.200.100', '7.200 MHz plus 100 Hz (the way the screen shows it)'],
      ], [2.0 * inch, 5.0 * inch]) + [
      shot('c-freq', 'Typing a frequency. Tap <b>Set</b> to send it to the radio.', width=3.4 * inch),
      P('The smaller number under the large one is the other VFO. Tap it to type a frequency for that VFO. The active VFO does not change.'),
      *H2('VFO buttons'),
      ] + table([
          ['Button', 'What it does'],
          ['A and B', 'Choose VFO A or VFO B as the active VFO. These two buttons are greyed out in USB mode; use A/B instead.'],
          ['VFO / MEM', 'Switch between VFO operation and memory-channel operation. It reads <b>MEM</b> when the radio is on a memory channel.'],
          ['A/B', 'Swap VFO A and VFO B. What was in B becomes A and what was in A becomes B.'],
          ['A→B', 'Copy VFO A into VFO B. Not available while on a memory channel.'],
          ['Split', 'Turn split operation on or off. <b>SPLIT</b> appears next to the other VFO\'s frequency while it is on.'],
          ['Lock', 'Lock the radio\'s dial so a bump of the tuning knob does not change the frequency. Highlighted while on.'],
      ], [1.2 * inch, 5.8 * inch]) + [
      *H2('Memory mode'),
      P('When the radio is on a memory channel the large display shows <b>MEM</b> with the channel number, the channel\'s name and its '
        'frequency. Tap <b>MEM</b> (or the A/B button) to return to VFO operation.')]

# ---------------------------------------------------------------- 5 band mode bw memories
s += [*sec('5. Band, Mode, filter width and Memories'),
      P('These four buttons are in the top row of the main screen.'),
      *H2('Band'),
      P('The Band button shows the band you are on (or <b>GEN</b> when the frequency is outside the amateur bands). Tap it and choose a '
        'band. The radio moves to a phone calling or well-used frequency on that band <b>and</b> switches to the usual mode for it. '
        '30 m has no phone allocation, so it goes to its digital frequency in DATA-U.'),
      shot('c-band', 'The Band picker. The band you are on is highlighted.', width=4.4 * inch),
      ] + table([
          ['Band', 'Frequency (MHz)', 'Mode', 'Band', 'Frequency (MHz)', 'Mode'],
          ['160 m', '1.900', 'LSB', '12 m', '24.950', 'USB'],
          ['80 m', '3.900', 'LSB', '10 m', '28.400', 'USB'],
          ['60 m', '5.3465', 'USB', '6 m', '50.125', 'USB'],
          ['40 m', '7.200', 'LSB', '2 m', '146.520', 'FM'],
          ['30 m', '10.136', 'DATA-U', '70 cm', '446.000', 'FM'],
          ['20 m', '14.300', 'USB', '', '', ''],
          ['17 m', '18.130', 'USB', '', '', ''],
          ['15 m', '21.300', 'USB', '', '', ''],
      ], [0.8 * inch, 1.4 * inch, 1.0 * inch, 0.8 * inch, 1.4 * inch, 1.0 * inch]) + [
      P('60 m is channel 2 of the 60 m band (dial frequency, USB).', small),
      *H2('Mode'),
      P('The Mode button shows the radio\'s current mode. Tap it and choose from LSB, USB, CW-U, CW-L, FM, FM-N, DATA-FM, AM, AM-N, '
        'DATA-L, DATA-U, RTTY-L and RTTY-U.'),
      shot('c-mode', 'The Mode picker.', width=4.4 * inch),
      *H2('BW (filter width)'),
      P('The BW button shows the width of the radio\'s receive filter in hertz. Tap it to choose a different width. The choices depend '
        'on the mode (SSB, CW or digital) and on whether narrow (NAR) is on. Changing the width here also switches on the radio\'s '
        'WIDTH function, so the new width takes effect immediately.'),
      shot('c-bw', 'The BW picker. Widths offered in DATA-U.', width=4.2 * inch)]

s += [*H2('Memories'),
      P('Tap <b>Memories</b> to see the radio\'s memory channels in a table. The first time it opens it reads every channel from the '
        'radio, which takes a few seconds; the meters pause while it reads. Tap <b>Rescan</b> to read them again.'),
      shot('07-memories', 'The Memory channels screen. The channel the radio is on is highlighted.'),
      ] + B([
          '<b>Go to a channel.</b> Tap a row. The radio switches to memory mode (if needed) and selects that channel.',
          '<b>Empty slots</b> are listed too, marked <i>empty</i>, with an <b>Add</b> button.',
          '<b>Channels above 99</b> (the special channels) appear only when they hold something, and they cannot be edited here.',
          'While the radio is transmitting, channel changes are switched off.']) + [
      *H2('Adding or editing a channel'),
      P('Tap <b>Add</b> on an empty slot or <b>Edit</b> on a used one. Fill in:'),
      ] + B(['<b>Frequency (MHz)</b>, from 0.03 to 56. A new channel starts with the frequency the radio is on.',
             '<b>Name</b>, up to 12 characters. The radio does not accept the letter X in a name, and it stores letters in lower case.',
             '<b>Mode</b>, <b>Tone</b> (Off, CTCSS T/R or CTCSS enc) and <b>Repeater shift</b> (Simplex, + or −).']) + [
      Spacer(1, 4),
      P('Tap <b>Save to radio</b>. The app writes the channel and reads it back to check it. A green message says when the channel is saved.'),
      shot('c-memadd', 'Adding a channel.', width=2.6 * inch)] + box(
          'Saving writes straight into the radio\'s memory. Before your first save in each session the app stores a backup of the channel '
          'list in its files folder on the tablet.', 'Careful') + [
      P('A memory channel does not store a filter width. The radio keeps the width setting for the mode you are using.', small)]

# ---------------------------------------------------------------- 6 function buttons
s += [*sec('6. Tune, IPO/AMP and ATT'),
      *H2('Tune'),
      P('What the Tune button does depends on the <b>Tune button</b> setting (see section 14). There are two choices.'),
      P('Radio\'s tuner', h2),
      P('Tap <b>Tune</b> to start the radio\'s own antenna tuner. The radio transmits briefly while it tunes, and the button shows red '
        'while the radio is transmitting.'),
      *H2('Carrier for external tuner'),
      P('Use this when you have an external automatic tuner that tunes when it sees a signal.'),
      ] + B([
          'Tap <b>Tune</b>. The radio switches to AM, sets the carrier power you chose in Settings (5 to 10 W) and sends a steady carrier.',
          'The button turns red and counts down (for example <b>Tune 52s</b>). Your tuner tunes while the carrier is on.',
          'Tap the button again to stop. It also stops by itself after 1 minute, or if you stop the transmission at the radio.',
          'When it stops, the app puts your mode, filter width and power back the way they were.']) + [
      *H2('IPO / AMP'),
      P('This button shows <b>IPO</b> when the receiver\'s preamplifier is bypassed (the radio\'s IPO setting) and <b>AMP</b> when the '
        'preamplifier is on. Tap it to switch between the two.'),
      *H2('ATT'),
      P('Turns the radio\'s 12 dB attenuator on or off. Highlighted while it is on.'),
      *H2('Why do some buttons look different?'),
      P('A filled (highlighted) chip is <b>on</b>; an outlined one is <b>off</b>. A greyed-out button is not available right now, '
        'for example the CW panel controls when the radio is not in a CW mode.')]

# ---------------------------------------------------------------- 7 meters and history
s += [*sec('7. Meters and signal history'),
      *H2('Meters'),
      ] + table([
          ['Meter', 'What it shows'],
          ['S', 'Received signal strength in S-units (S1 to S9, then +20, +40 and +60 dB).'],
          ['PWR', 'Transmit power in watts.'],
          ['SWR', 'Standing-wave ratio of the antenna while you transmit.'],
          ['ALC', 'Automatic level control, as a percentage. Updates while transmitting.'],
          ['COMP', 'Speech-compression level, as a percentage. Updates while transmitting.'],
          ['ID', 'Drain current of the power amplifier, as a percentage. Updates while transmitting.'],
      ], [1.0 * inch, 6.0 * inch]) + [
      P('The meter scales are approximations of the radio\'s own meter. Use them to see trends and relative levels.', small),
      *H2('Bars or needles'),
      P('The meters can be shown as straight bars (the default) or as round needle gauges. Change this in Settings under <b>Meters</b>.'),
      shot('18-main-analog', 'The needle-gauge (Meter) style.'),
      *H2('Signal history'),
      P('The <b>Signal history</b> tab of the card under the buttons draws the recent past, newest on the right. It shows the <b>S</b> '
        'meter (blue) while you receive and the <b>PWR</b> (green) and <b>SWR</b> (amber) traces while you transmit. Each trace is a solid '
        'line with a lighter fill of the same colour underneath. Transmit periods are shaded red.'),
      P('The same card has a second tab, <b>Waterfall</b> (section 15), with a FLRig connection or with an SDR plugged into the '
        'tablet. The card grows a little taller while the waterfall is showing.')]

# ---------------------------------------------------------------- 8 sliders
s += [*sec('8. Sliders'),
      P('Four sliders sit beside the meters.'),
      ] + table([
          ['Slider', 'What it controls'],
          ['VOL', 'Receiver volume (AF gain), 0 to 100.'],
          ['RF', 'RF gain, 0 to 100.'],
          ['MIC', 'Microphone gain, 0 to 100.'],
          ['PWR W', 'Transmit power in watts, 5 to 100.'],
      ], [1.2 * inch, 5.8 * inch]) + [
      *H2('How to use them')] + B([
          '<b>Drag</b> the slider. The new value is sent to the radio when you lift your finger.',
          'Tap the <b>left or right arrow</b> at the ends to change the value by one step.',
          'For <b>PWR W</b>, tap the number to type an exact power (5 to 100 W).',
          'The sliders follow the radio. If you turn a knob on the radio, the slider moves to match.']) + [
      P('The radio itself does not allow a transmit power below 5 W when set from the app.', small)]

# ---------------------------------------------------------------- 9 DSP
s += [*sec('9. DSP &amp; IF screen', 4.6),
      P('Tap the filter graph at the top right of the main screen to open the <b>DSP &amp; IF</b> screen. It gathers the radio\'s '
        'receive filter and noise controls in one place. The screen reads the radio a few times a second, so it follows changes made '
        'at the radio.'),
      shot('10-dsp-top', 'The DSP &amp; IF screen (top).'),
      *H2('The graph'),
      P('The graph at the top is a picture of your settings. The blue shape is the filter passband; its width and position follow the width '
        'and IF shift. The vertical lines mark the pitch centre (grey), the notch (amber), the contour (purple) and, in CW modes, the APF (green).'),
      *H2('Controls'),
      ] + table([
          ['Control', 'What it does'],
          ['SHIFT', 'IF shift on/off, with a slider from −1200 to +1200 Hz in 20 Hz steps. It slides the passband up or down to move away from interference.'],
          ['NAR and WIDTH', 'NAR switches the radio\'s narrow filter on or off. The WIDTH slider steps through the filter widths available for the current mode and narrow/wide setting; the width in hertz is shown at the right.'],
          ['NB', 'Noise blanker on/off, with a level from 0 to 10.'],
          ['CONTOUR', 'Contour filter on/off, with the centre frequency (10 to 3200 Hz). Below it, <b>Level</b> (−40 to +20 dB) sets how deep or high the contour is and <b>Width</b> (1 to 11) sets how broad it is.'],
          ['NOTCH', 'Manual notch on/off, with its frequency (10 to 3200 Hz in 10 Hz steps). <b>Notch width</b> chooses Narrow or Wide.'],
          ['APF', 'Audio peak filter on/off (CW modes only), with an offset of −250 to +250 Hz. <b>APF width</b> chooses Narrow, Medium or Wide.'],
          ['DNF', 'Digital notch filter (auto notch) on/off. It removes steady tones automatically.'],
          ['DNR', 'Digital noise reduction on/off, with a level from 1 to 15.'],
          ['AGC', 'Automatic gain control: Off, Fast, Mid, Slow or Auto.'],
      ], [1.5 * inch, 5.5 * inch]) + [
      shot('11-dsp-bottom', 'The lower part of the DSP &amp; IF screen (scroll to reach it).'),
      *H2('Using the controls')] + B([
          'A filled button is on; an outlined button is off. Tap it to switch.',
          'Most sliders work only while their button is on. Drag one, or tap the arrow at either end to move one step.',
          'Tap <b>Close</b> (or outside the screen) to return to the main screen.',
          'While the radio is transmitting the screen is greyed out and its controls cannot be changed.']) + box(
          'Contour level and width, notch width and APF width are settings the radio does not report back. The controls for them show what you '
          'last set from this app, not necessarily what the radio holds.')
s += [shot('24-dsp-cw', 'In a CW mode the DSP &amp; IF screen also shows APF width and the APF control.')]

# ---------------------------------------------------------------- 10 mic EQ
s += [*sec('10. TX equalizer and speech processor'),
      P('Tap the <b>MIC EQ</b> graph on the main screen to open the transmit audio screen. Here you shape the sound of your voice on '
        'SSB: a three-band equalizer, the speech processor, mic gain and the transmit monitor.'),
      shot('22-mic-eq', 'The TX parametric EQ screen in USB mode.'),
      ] + table([
          ['Control', 'What it does'],
          ['MIC EQ', 'Turns the microphone equalizer on or off.'],
          ['PROC', 'Turns the speech processor on or off.'],
          ['Processor off set / Processor on set', 'The radio keeps two sets of equalizer settings: one used when the processor is off and one used when it is on. Choose which set you are looking at and editing. When you open the screen it starts on the set that matches the processor\'s current state.'],
          ['The graph', 'Three handles, LOW, MID and HIGH. <b>Drag a handle left or right</b> to change its frequency and <b>up or down</b> to change its gain (+10 to −20 dB). Dragging a band that is off switches it on. The change is sent when you lift your finger.'],
          ['Width', 'Under the graph, each band has a width slider (1 to 10). Wider settings affect a broader range of frequencies.'],
          ['Off', 'Switches that band off.'],
          ['Speech processor Level', 'Processor level, 0 to 100.'],
          ['Mic gain', 'Microphone gain, 0 to 100. (This is the same control as the MIC slider on the main screen.)'],
          ['TX monitor', 'Lets you hear your own transmit audio. A switch turns it on; the Level slider sets the volume.'],
      ], [1.9 * inch, 5.1 * inch]) + box(
          'In a DATA mode (DATA-U, DATA-L or DATA-FM) the audio comes from the USB audio connection, so the mic equalizer and processor '
          'do not apply and the screen says so. Switch to SSB to use them.') + box(
          'The radio does not report the three EQ bands back, so the graph shows what you last set from this app. It remembers those '
          'settings on this tablet.')

# ---------------------------------------------------------------- 11 CW / FM / VOICE
s += [*sec('11. CW, FM and VOICE panels'),
      P('Under the Tune row are three buttons: <b>CW</b>, <b>FM</b> and <b>VOICE</b>. Tap one to open its panel beside the buttons; tap '
        'it again to close the panel. The panel shows the radio\'s own settings and keeps them up to date.'),
      *H2('CW panel'),
      shot('23-cw-panel', 'The CW panel, with the radio in CW-U.'),
      ] + table([
          ['Control', 'What it does'],
          ['Keyer', 'Turns the radio\'s electronic keyer on or off.'],
          ['Break-in', 'Turns CW break-in on or off.'],
          ['Spot', 'Turns on the spotting tone so you can match your pitch to a station you are tuning.'],
          ['Speed', 'Keyer speed, 4 to 60 words per minute.'],
          ['Pitch', 'CW pitch, 300 to 1050 Hz. This also sets where the CW filter is centred.'],
          ['Delay', 'Break-in delay, 30 to 3000 ms.'],
          ['Memory 1 to 5', 'Plays the radio\'s own keyer memories 1 to 5. <b>The radio transmits while a memory plays.</b>'],
      ], [1.5 * inch, 5.5 * inch]) + [
      P('These controls work only in a CW mode. In other modes the panel says <i>CW mode only</i> and the controls are greyed out.', small),
      *H2('FM panel'),
      shot('25-fm-panel', 'The FM panel, with the radio in FM.'),
      ] + table([
          ['Control', 'What it does'],
          ['Shift', 'Repeater shift: Simplex, + or −. Available only in an FM mode.'],
          ['Tone', 'Tone mode: Off, Enc (encode only), Enc/Dec (encode and decode) or DCS.'],
          ['Freq / Code', 'Shows the current CTCSS tone (or DCS code when Tone is DCS). Tap it to pick a different one from the list.'],
          ['SQL', 'Squelch level, 0 to 100. Drag the slider, or tap the number to type a value.'],
      ], [1.5 * inch, 5.5 * inch])
s += [*H2('VOICE panel'),
      shot('20-voice-panel', 'The VOICE panel.'),
      P('The radio\'s voice memories 1 to 5 hold recorded messages. Choose <b>Play</b> or <b>Record</b> and then a message number.'),
      ] + B([
          '<b>Play.</b> Tap a number to play that message. Tap <b>Stop</b> to stop it. The radio transmits the message when its own PB '
          'setting is on; otherwise it plays only through the radio\'s speaker.',
          '<b>Record.</b> Tap a number and confirm. Recording starts at once and uses the radio\'s microphone. Tap the number again '
          '(it shows red while recording) or <b>Stop</b> to finish. Recording replaces whatever was stored in that message.',
          'Voice messages work in SSB and AM modes.'])

# ---------------------------------------------------------------- 12 bottom card
s += [*sec('12. The card at the bottom (FLRig connection)', 4.8),
      P('When you are connected through FLRig, the bottom of the main screen has a card with three tabs: <b>Bands</b>, <b>CW</b> and '
        '<b>FLRig buttons</b>. With a direct USB connection this card is not shown.'),
      *H2('Bands tab'),
      shot('c-bandstrip', 'The Bands tab.'),
      P('The <b>Bands</b> tab lists the digital-mode calling frequencies (the usual FT8 frequencies) for each band from 160 m to 70 cm, '
        'for use with digital-mode software on the computer. Tap a band to go straight to its frequency. This changes the frequency only, '
        'not the mode.'),
      *H2('CW tab (FLRig connection)'),
      shot('27-bottom-cw', 'The CW tab.'),
      P('The <b>CW</b> tab sends Morse through FLRig:')] + B([
          '<b>F1 to F6</b> are six message buttons. Tap one to send it; press and hold one to edit its text. The messages are kept on the tablet.',
          '<b>Stop</b> stops the message that is being sent.',
          '<b>WPM</b> sets the sending speed.',
          '<b>Send text</b> sends whatever you type in the box when you tap <b>Send</b>.']) + [
      P('FLRig needs its CW keying set up for this to work. With a direct USB connection use the keyer memories in the CW panel '
        'instead (section 11).', small),
      *H2('FLRig buttons tab'),
      P('The third tab shows 24 numbered buttons. Each one runs the matching user command button that you set up in '
        'FLRig (Config, Setup, Commands).')]

# ---------------------------------------------------------------- 13 console
s += [*sec('13. CAT console'),
      P('Tap <b>CAT console</b> on the status card to show a console at the bottom of the screen. It sends a command to the radio '
        'exactly as you type it and shows the radio\'s reply. Each command ends with a semicolon. It is meant for experienced users who '
        'know the radio\'s command set; most people will never need it.'),
      shot('28-console', 'The console after sending a read command. Lines beginning with &gt; were sent, and lines beginning with &lt; are the radio\'s replies.'),
      P('Tap <b>CAT console</b> again to hide it.')]

# ---------------------------------------------------------------- 14 settings
s += [*sec('14. Settings', 4.6),
      P('Tap <b>Settings</b> on the status card. The settings panel slides in over the right side, so you can see the main screen change '
        'as you adjust things. Tap <b>Close</b> or tap outside the panel to close it.'),
      shot('14-settings-top', 'The Settings panel.'),
      *H2('Display'),
      P('<b>Dark mode</b> switches between the dark theme (default) and the light theme. Each theme keeps its own colour changes.'),
      shot('19-main-light', 'The light theme.', width=5.2 * inch),
      *H2('Meters'),
      P('Choose <b>Bar</b> for straight level bars or <b>Meter</b> for round needle gauges (see section 7).'),
      *H2('Tune button'),
      P('Choose how the Tune button works (see section 6).'),
      ] + B(['<b>Radio\'s tuner.</b> Tune starts the radio\'s own antenna tuner.',
             '<b>Carrier for external tuner.</b> Tune becomes an on/off button that sends a steady low-power carrier for an external tuner.',
             '<b>Carrier power</b> (shown when the carrier option is chosen). Slide from 5 to 10 W to set the power of the carrier.']) + [
      shot('17-settings-tune-radio', 'With Radio\'s tuner chosen the carrier-power slider is hidden.', width=5.2 * inch),
      *H2('Waterfall'),
      Table([[[P('<b>Panadapter port</b> is the network port of Cat Explorer Panadapter on the PC (7366 unless you changed it there). '
                 'Type a new number and tap <b>Save</b>.'),
               P('<b>Colours</b> picks the waterfall\'s colour theme. Tap it for a list with a preview strip of each one, quiet on the left '
                 'and loud on the right. <b>Cat Explorer</b> is the app\'s own; the other 28 are the palettes that come with JS8Call. '
                 'Changing it recolours the whole waterfall at once.'),
               P('<b>Band edges (US)</b> turns the band-edge lines and dimming on or off (section 15).'),
               P('<b>License bars</b> chooses the bars under the waterfall: <b>Off</b>, one class (<b>Technician</b>, <b>General</b>, '
                 '<b>Advanced</b> or <b>Extra</b>), or <b>All four</b>. Pick your own class to see at a glance where you may transmit.'),
               P('<b>SDR gain</b> sets the gain of an SDR plugged into the tablet: <b>Auto</b>, or <b>Manual</b> with a slider. It is '
                 'the same setting as the <b>Gain</b> button on the waterfall (section 15).')],
              img('31-settings-waterfall', 2.5 * inch)]], colWidths=[4.4 * inch, 2.6 * inch],
            style=TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (0, 0), 0)])),
      *H2('Colours'),
      P('The colour list lets you change 32 colours, separately for dark and light mode. They are grouped as General, Text, Meter bars, '
        'Signal history and Graphs. A swatch shows each colour as it is now, and the word <i>changed</i> appears next to any colour that '
        'differs from the original.'),
      shot('15-settings-colours', 'The colour list.', width=5.2 * inch),
      *H2('Changing a colour'),
      P('Tap a colour to open its picker. Changes show live on the screen behind it.'),
      ] + B(['Drag across the large spectrum to pick a shade. Across changes the colour; down fades it to grey.',
             'Slide the <b>Lighter / darker</b> bar to make it lighter or darker.',
             'Or type a colour into the <b>Hex colour</b> box (for example #0B0F14).',
             'The two swatches compare the <b>Default</b> colour with your <b>New</b> one.',
             '<b>Use default</b> puts the original colour back, <b>Cancel</b> discards your change and <b>OK</b> keeps it.']) + [
      shot('16-colour-editor', 'The colour picker.', width=5.2 * inch),
      P('At the bottom of the colour list, <b>Reset all colours to default</b> puts back every colour for the mode you are currently in '
        '(dark or light). Your other settings are not changed.')]

# ---------------------------------------------------------------- 15 waterfall
s += [*sec('15. Waterfall and panadapter', 4.6),
      P('The <b>Waterfall</b> tab of the card under the buttons shows signals as coloured traces that scroll down the screen, with a '
        'live spectrum line above them. Tap a signal to tune to it. The radio itself has no band-scope output, so the picture comes from '
        'somewhere else, in one of three ways:')] + B([
          '<b>From the radio\'s audio</b>, through FLRig: a small program on the FLRig PC, <b>Cat Explorer Panadapter</b>, records the '
          'sound card wired to the radio and sends it to the tablet. You see everything inside your receive filter, about 0 to 4 kHz. '
          'No extra hardware needed.',
          '<b>From an SDR on the PC</b>, through FLRig: the Panadapter runs an SDR (an RTL-SDR, or an SDRplay, Airspy, HackRF and '
          'others) and sends up to 200 kHz of the band around your dial, with real frequencies.',
          '<b>From an SDR plugged into the tablet</b>, over a direct USB connection: the app runs the SDR itself, no PC needed. The same '
          'wide view as an SDR on the PC.']) + [
      Spacer(1, 4)] + box('Over a direct USB connection only an SDR plugged into the tablet works: the radio\'s USB port carries no '
                          'audio, so there is no audio waterfall that way.') + [
      *H2('Setting up the PC'),
      P('In the Cat Explorer folder on the PC, run <font face="Mono">pc-panadapter/install.sh</font>. It adds <b>Cat Explorer '
        'Panadapter</b> to the <b>Ham Radio</b> folder of the start menu. Run it with <font face="Mono">sudo</font> to install it for '
        'every user, or with <font face="Mono">--uninstall</font> to remove it. It then offers to install the extra software an SDR '
        'needs (numpy and SoapySDR, from your system\'s software source); answer <b>y</b> if you will use an SDR on the PC. It asks for '
        'your password for that part. The README on GitHub has the details, including the one-time setup for an RTL-SDR on HF.'),
      shot('34-panadapter-window', 'The Cat Explorer Panadapter window on the PC.', width=4.6 * inch),
      P('Open it from the menu, pick the <b>Source</b> (the radio\'s sound card, or an SDR: <b>RTL-SDR — wide panadapter</b>, or '
        'another SDR shown as <b>… — wide panadapter (SoapySDR)</b>) and press '
        '<b>Start</b>. The window shows whether it is running, the PC\'s addresses, which tablets are connected and the audio level. '
        'You can close the window: the panadapter keeps running until you open it again and press <b>Stop</b>. If the window or the app '
        'says the audio is too loud, turn the sound card\'s input level down on the PC.'),
      *H2('Audio waterfall'),
      shot('33-waterfall-audio', 'The audio waterfall on the 40 m FT8 frequency, with all four license bars.'),
      P('The scale along the bottom is the audio tone in kHz. What a tap does depends on the mode:')] + B([
          '<b>CW:</b> the signal you tap moves onto your CW pitch.',
          '<b>DATA:</b> the signal moves to 1500 Hz (the dashed line), the middle of most digital-mode programs.',
          '<b>SSB:</b> the point you tap becomes the carrier, so tap the low edge of a USB voice signal (the high edge in LSB).',
          'Tapping does nothing in AM and FM.']) + [
      P('<b>Listen</b> also plays the radio\'s audio on the tablet. <b>Range</b> sets how many dB the colours cover: a smaller range '
        'makes weak signals brighter.'),
      *H2('SDR panadapter'),
      shot('30-waterfall-sdr', 'The SDR view on 40 m (an RTL-SDR on the PC) with the General license bar.'),
      P('With an SDR the scale is the real frequency in MHz. The red line is your dial and the shaded area is your receive '
        'filter. <b>Span</b> sets how wide a slice you see, from 200 kHz down to 10 kHz. The PC follows the radio\'s frequency through '
        'FLRig, so the picture moves with your dial and changes band when you do. Tap a signal to tune to it; in CW and DATA the tap snaps '
        'to the nearest signal, and in DATA the signal lands at 1500 Hz in your audio. The picture pauses while you transmit.')] + box(
          'Never connect the SDR to the radio\'s antenna socket: one transmission would destroy it. Give it its own receive antenna, '
          'or use a T/R switch keyed by the radio.', 'Careful') + [
      *H2('SDR on the tablet (USB connection)'),
      P('Plug the radio and the SDR into a powered USB-C hub on the tablet and tap <b>Connect over USB</b>. When the app finds a '
        'supported SDR, the <b>Waterfall</b> tab appears; the first time, Android asks whether Cat Explorer may use it: tap <b>OK</b>. '
        'The app keeps the SDR tuned near the radio\'s dial, just as the PC does.'),
      shot('35-usb-sdr-gain', 'An RTL-SDR on the tablet, with the Gain pop-out open.'),
      P('The <b>Gain</b> button on the waterfall bar shows the gain setting. Tap it for <b>Auto</b> or <b>Manual</b>; Manual shows a '
        'slider through the SDR\'s own gain steps. Higher gain shows weaker signals; lower it if the waterfall says the SDR is '
        'overloaded. On an RTL-SDR Blog V3 below 24 MHz the SDR samples HF directly, so gain has no effect there and the button is '
        'greyed; it applies on 10 m and 6 m. The same setting is in Settings (section 14).'),
      ] + table([
          ['SDR', 'Covers', 'Gain'],
          ['RTL-SDR (RTL-SDR Blog V3 or V4, and other RTL2832U dongles)', '500 kHz – 1.7 GHz (HF by direct sampling on a V3)', 'dB steps'],
          ['Airspy R2 or Mini', '24 MHz – 1.8 GHz: 10 m and up only', 'Auto, or levels 0–21'],
          ['Airspy HF+ or HF+ Discovery', '0 – 31 MHz and 60 – 260 MHz: not 6 m', 'Auto, or 0–48 dB'],
          ['HackRF One', '1 MHz – 6 GHz', '0–100 dB (no real Auto)'],
      ], [2.6 * inch, 2.6 * inch, 1.8 * inch]) + [
      P('If the dial is outside what the SDR covers, the waterfall says so. Only the RTL-SDR has been tried with Cat Explorer on '
        'real hardware so far; the others use their makers\' published drivers.', small),
      ] + box('An <b>SDRplay</b> cannot be run by the tablet: SDRplay\'s driver is closed and not available for Android apps. Plug it '
              'into the FLRig PC and use it through the Panadapter there (see <b>SDRplay on the PC</b> below).') + [
      P('A USB hub with an Ethernet adapter or a charger can add steady, evenly spaced carriers to the picture. If you see lines that '
        'move when the SDR retunes, try another hub or unplug its extras.', small),
      *H2('Other SDRs on the PC'),
      P('Besides the RTL-SDR, the Panadapter can run any SDR that has a ' + "<link href='https://github.com/pothosware/SoapySDR' color='#0B6FA4'><u>SoapySDR</u></link>" + ' module: SDRplay, Airspy, Airspy HF+, HackRF, '
        'LimeSDR, PlutoSDR and others. Let the installer add the SDR software (or install <font face="Mono">python3-soapysdr</font> '
        'and <font face="Mono">soapysdr-module-all</font> yourself), plug the SDR in, press <b>Refresh</b> in the Panadapter window and '
        'choose it under <b>Source</b>. Its <b>SDR gain</b> list is the SDR\'s overall gain; if the SDR\'s automatic gain makes the '
        'window or the app say it is overloaded, pick a fixed gain instead.'),
      *H2('SDRplay on the PC'),
      P('SDRplay radios (RSP1A, RSP1B, RSPdx, RSPduo and others) need SDRplay\'s own driver on the PC as well as SoapySDR. Set it up '
        'once, in this order:')] + B([
          '<b>SDRplay\'s API</b> (their driver, free but closed): download the Linux <b>API</b> installer from ' + "<link href='https://www.sdrplay.com/downloads/' color='#0B6FA4'><u>sdrplay.com/downloads</u></link>" + ', make it '
          'runnable and run it with <font face="Mono">sudo</font>, accepting SDRplay\'s licence. It installs a background service that the '
          'SDR needs.',
          '<b>SoapySDR\'s SDRplay module</b>, ' + "<link href='https://github.com/pothosware/SoapySDRPlay3' color='#0B6FA4'><u>github.com/pothosware/SoapySDRPlay3</u></link>" + ': run the Panadapter installer again with '
          '<font face="Mono">--sdr-software</font> and it builds and installs this module for you (or build it yourself as its page '
          'describes).',
          'Unplug and replug the SDRplay, press <b>Refresh</b> in the Panadapter window and choose it under <b>Source</b>.']) + [
      P('SoapySDR itself is at ' + "<link href='https://github.com/pothosware/SoapySDR' color='#0B6FA4'><u>github.com/pothosware/SoapySDR</u></link>" + '. To check the SDRplay is found, run <font face="Mono">SoapySDRUtil --find</font> in a '
        'terminal; it should list it.', small),
      *H2('Band edges and license bars'),
      shot('32-waterfall-edge', 'The bottom of 40 m with all four license bars. Below 7.000 MHz is out of band and dimmed.'),
      P('Both views mark the US (FCC) band plan:')] + B([
          'A <b>solid white line</b> is a band edge. Everything outside the band is dimmed.',
          'A <b>dashed line</b> is a sub-band edge, where some license class\'s privileges change. Each line is labelled with its '
          'frequency at the top.',
          'The <b>license bars</b> along the bottom show where each class may transmit, coloured by what is allowed there: '
          '<b>yellow</b> CW only, <b>blue</b> CW and data, <b>green</b> phone and CW, <b>purple</b> all modes.',
          '<b>Red</b> on a bar means only a higher class may transmit there, so a licensee of that class must not.',
          'The letters at the left of the bars are the classes: E Extra, A Advanced, G General, T Technician. A key under the waterfall '
          'repeats the colours.']) + [
      P('Choose which bars to show, or turn the edges off, in Settings (section 14). The plan covers 160 m to 6 m plus 2 m and 70 cm; '
        '60 m shows its five channels. The audio waterfall shows edges only in SSB, CW and DATA modes.'),
      ] + box('The band plan is a guide. The rules can change, so check the current FCC rules before relying on it.') + [
      *H2('Colours'),
      P('Pick the waterfall\'s colour theme in Settings under <b>Waterfall</b>. The FT8 picture above uses <b>Scope</b>; the others '
        'use the dark-blue end of the same theme on a quiet band.')]

# ---------------------------------------------------------------- 16 usb vs flrig
s += [*sec('16. USB and FLRig: what is available'),
      P('Almost everything works the same either way. This table lists the differences.'),
      ] + table([
          ['Feature', 'Direct USB', 'FLRig'],
          ['Frequency, mode, band, width, memories', 'Yes', 'Yes'],
          ['DSP, EQ, CW, FM and VOICE panels', 'Yes', 'Yes'],
          ['Meters, sliders, Tune, IPO/ATT', 'Yes', 'Yes'],
          ['A and B buttons (choose VFO)', 'Greyed out. Use A/B.', 'Yes'],
          ['Card at the bottom (digital-mode bands, CW typed text and F1 to F6 messages, FLRig buttons)', 'Not shown. Use the CW panel\'s keyer memories 1 to 5.', 'Yes'],
          ['Waterfall and panadapter', 'With an SDR plugged into the tablet (no audio waterfall)', 'Yes, with Cat Explorer Panadapter on the FLRig PC (audio or SDR)'],
          ['Needs a computer', 'No', 'Yes'],
          ['Other software can use the radio at the same time', 'No (the tablet holds the connection)', 'Yes, through FLRig'],
      ], [2.8 * inch, 2.4 * inch, 1.8 * inch]) + [
      *H2('Quick reference'),
      ] + table([
          ['I want to…', 'Do this'],
          ['Go to a band', 'Tap the Band button and choose the band.'],
          ['Type a frequency', 'Tap the large frequency, type it, tap Set.'],
          ['Change the filter width', 'Tap the BW button, or open DSP &amp; IF and use the WIDTH slider.'],
          ['Jump to a memory channel', 'Tap Memories, then tap the row.'],
          ['Tune my antenna', 'Tap Tune (set how it works in Settings).'],
          ['Lock the dial', 'Tap Lock.'],
          ['Swap VFO A and B', 'Tap A/B.'],
          ['See signals around my frequency', 'Tap the Waterfall tab (through FLRig with the Panadapter running, or with an SDR on the tablet).'],
          ['Change the SDR\'s gain', 'Tap Gain on the waterfall bar (an SDR on the tablet), or SDR gain in the Panadapter window (an SDR on the PC).'],
          ['Tune to a signal I can see', 'Tap it on the waterfall.'],
          ['See where my license lets me transmit', 'Settings, Waterfall, License bars: choose your class.'],
          ['Change the colours', 'Settings, then tap a colour.'],
          ['Disconnect', 'Tap Disconnect on the status card.'],
      ], [2.8 * inch, 4.2 * inch])

doc.multiBuild(s)
print('built', OUT)
