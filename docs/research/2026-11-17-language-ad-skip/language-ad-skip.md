# Skipping inserted ads by spoken language (2026-11-17)

Raw, non-normative research note behind [D99](../../PLAN.md#3-key-decisions). The scratch files of the session (audio, scripts, per-window CSVs) are not in the repository.

## Question

Many English podcasts carry dynamically inserted ads in the listener's local language: German or French ads in English shows heard in Switzerland, for example. Can the app find stretches of speech that are not in the podcast's language and skip them, reliably and cheaply?

## Method

- Seven English episodes, the newest full episode of each feed, fetched on 2026-11-17 from a Swiss IP address, so each host inserted the ads it targets at Switzerland.
- Spoken-language identification (LID) over 4 s windows, 2 s hop, with Whisper's language-ID head ([faster-whisper](https://github.com/SYSTRAN/faster-whisper), int8, CPU). Each window yields the top language and P(English).
- Ground truth: every flagged stretch was transcribed with Whisper and checked with a second LID model, SpeechBrain's [VoxLingua107 ECAPA](https://huggingface.co/speechbrain/lang-id-voxlingua107-ecapa) (its labels on this audio proved unreliable; see below). Nobody listened to the audio. English ads were looked for only in samples (the first 90 s and 60 s at the start, middle and end).
- A frame-header scan of each MP3 looked for stitch marks: mid-stream tags, format changes and junk between frames.
- Runs on an Apple M4 Max CPU while another build used most of its cores, so the timings are pessimistic and not a benchmark.

## Episodes

| Show | Host | Length | Foreign-language ads |
|---|---|---|---|
| The Rest Is History | Megaphone | 70 min | 3 German breaks (pre, mid, post) |
| Science Vs | Megaphone | 36 min | 3 German breaks |
| The Bill Simmons Podcast | Megaphone | 72 min | 4 German breaks |
| The Rest Is Politics | Megaphone | 15 min | none |
| Global News Podcast | BBC via Triton | 32 min | none |
| Freakonomics Radio | Simplecast | 56 min | none |
| Stuff You Should Know | iHeart via Triton | 56 min | none |

The ten breaks hold 606 s of German ads for Swiss advertisers (coffee, a photo-gift shop, a finance app, an insurer); the same spots recur across all three shows. English ads were present but in English: a sponsor read, BBC and iHeart promos, a charity spot.

## Decision rules compared

A "run" is a stretch of windows whose P(English) is below 0.3, bridging one window that is not.

| Rule | Model | Ad audio skipped | Non-ad audio skipped |
|---|---|---|---|
| Any run of at least 6 s (skip every language other than English) | Whisper-base, 2 s hop | 604 of 606 s | 226 s in 23 places, plus 6 s of overrun at ad edges |
| Runs of at least 8 s with at least half the windows in German, French, Italian or Romansh | Whisper-base, 2 s hop | 594 of 606 s | 6 s of overrun at ad edges only |
| Same | Whisper-base, 4 s hop (every other window) | 576 of 606 s | 8 s of overrun only |
| Same | Whisper-tiny, 4 s hop | 554 of 606 s, all 10 breaks | 2 s of overrun only |
| Any run of at least 6 s | SpeechBrain ECAPA, 2 s hop | 584 of 606 s | 2,344 s |
| Ad-language rule | SpeechBrain ECAPA, 2 s hop | 64 of 606 s | 6 s |

Why "any other language" fails: English spoken with a strong accent was labelled Arabic, Welsh, Swahili or Latin, with a run's mean P(English) as low as 0.03 and runs up to 28 s long. One 15-minute interview would have lost 16% of its audio. A genuine Arabic clip inside a BBC news report would also have been skipped. Restricting skips to runs of at least 8 s with at least half their windows in the languages the ads are targeted in eliminated all 23 false positives in this sample: 21 were labelled with other languages, and the two with a German-labelled window lasted only 6 s, below the 8 s minimum.

Why the ECAPA model is out: on produced podcast audio it read about 39 minutes of English as foreign and labelled the Swiss German ads as Dutch, Afrikaans or Estonian. It is fast and Apache-2.0, but unusable here.

## Edges and stitch marks

- Starts are found to within one window. Ends overran by up to 4 s into the show (Bill Simmons, 30 s to 34 s). Whisper-tiny started one ad break about 9 s late.
- In the three Megaphone episodes with ads, each break began at a single plain-stereo MP3 frame inside joint-stereo audio, and the same mark recurred within the break every 30.07 s or 20.5 s, at each ad slot. The end of a break is not marked, and the ad-free episodes, including the Megaphone one, had none. These marks can snap starts exactly, but they do not detect ads on their own.

## Cost

| Model | Hop | Speed (audio time / CPU time) | CPU per hour of audio |
|---|---|---|---|
| Whisper-base (74M parameters) | 2 s | 9 to 10× | about 6 min |
| Whisper-tiny (39M parameters, 75 MB fp16 file; measured in int8) | 4 s | 36 to 46× | about 1.5 min |
| SpeechBrain ECAPA (21M parameters) | 2 s | 143 to 161× | under 30 s |

Language ID only; no transcription. Each Whisper call pads the 4 s window to Whisper's 30 s input, so a runtime that shortens the encoder context may cut the cost further (unverified). Phones were not measured.

## Licences

- Usable: Whisper weights ([MIT](https://github.com/openai/whisper/blob/main/LICENSE); the Hugging Face copies are Apache-2.0 or MIT), [faster-whisper](https://github.com/SYSTRAN/faster-whisper) and CTranslate2 (MIT), [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT, Java bindings), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0, Kotlin and Android packages). The licence of sherpa-onnx's pre-converted Whisper files is not stated, so a build would convert the original weights itself.
- Excluded: [MMS-LID](https://huggingface.co/facebook/mms-lid-126) (CC-BY-NC-4.0), ESPnet's VoxLingua107 model (built on the non-commercial MMS-1B encoder), NVIDIA's AmberNet (NGC terms), Silero's language classifier (licence unclear, removed upstream).

## Limits

- Ads in the podcast's own language, including host-read ads, pass through.
- Content in an ad language (a French-speaking guest, a German quote) would be cut.
- In this sample only Megaphone inserted local-language ads; other hosts may differ elsewhere.
- The rule was tuned on the same seven episodes. French ads, other regions and other hosts are untested.
- Inserted ads differ per request, so detections belong to one downloaded file. A stream's pre-roll plays before any analysis can run.

## Alternatives

- Comparing two downloads of one episode finds inserted ads in any language, but doubles traffic and appears close to Amazon's [US12190871](https://patents.google.com/patent/US12190871) (not a legal opinion).
- Full transcription with a language model ([Podly](https://github.com/jdrbc/podly_pure_podcasts), [MinusPod](https://github.com/ttlequals0/MinusPod)) also catches English ads, at many times the cost and usually on a server.
- Fingerprints of ads the user has confirmed would catch the recurring creatives, music-only parts included.
- Crowd-sourced timestamps (SponsorBlock-style) do not survive per-request ad insertion. Background: [ctrl.blog on programmatic podcast ads](https://www.ctrl.blog/entry/podcast-programmatic-adblock/).
