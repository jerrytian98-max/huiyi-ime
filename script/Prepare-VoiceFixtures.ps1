# SPDX-License-Identifier: GPL-3.0-or-later
# Synthetic speech from locally installed Windows TTS; no microphone or external service.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$assetPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\androidTest\assets'
$format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$examples = @(
    @{ Language = 'zh'; Voice = 'Microsoft Huihui Desktop'; Text = '嗯，明天下午三点开会，请提前发一下提纲，预算是一千五百元，还没有确定。' },
    @{ Language = 'en'; Voice = 'Microsoft Zira Desktop'; Text = 'Um, tomorrow at three P M we will meet. Please send the outline first. The budget is fifteen hundred dollars, but it is not confirmed yet.' }
)
foreach ($example in $examples) {
    $speaker = [System.Speech.Synthesis.SpeechSynthesizer]::new()
    try {
        $speaker.SelectVoice($example.Voice)
        $speaker.SetOutputToWaveFile((Join-Path $assetPath "speech-$($example.Language).wav"), $format)
        $speaker.Speak($example.Text)
    } finally { $speaker.Dispose() }
}
Write-Output 'Prepared synthetic Chinese and English PCM16 WAV fixtures at 16000 Hz.'
