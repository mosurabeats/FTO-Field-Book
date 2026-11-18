# FTO FieldBook


Phone app for a Field Training Officer: log trainee observations in the field, build each shift's
Daily Observation Report from them, and export it on the VCJTC FTEP-1 (Level II / III) Word form.

- **Notes**: quick observations tagged to the 40 SEGs, tone, call type, case #, remedial minutes.
- **DORs**: prepopulated from the shift's notes. Ratings 1–7 / NO with NAR, NRT, REM and TIME,
  ride numbering by phase, and the FTEP validation rules (narrative for 1, 2, 6, 7, REM, NRT; NRT only
  after 3 completed RTPs; least-acceptable only on failing shifts). Exports the filled FTEP-1 .docx, a PDF, or text.
- **RTPs**: Remedial Training Worksheets, tied to the NRT count.
- **Tasks**: the Standard Task Checklist (Discussed / Demonstrated / Performed), colour-coded by phase.
- **Progress**: trend per SEG, watch list, weekly-report data, and optional Claude drafting of weekly
  comments and the Phase Evaluation (needs an Anthropic API key in Trainee → Claude drafting).

## Run it
No build step. Serve the folder and open it in a browser:

    python3 -m http.server 8000

On a phone, open it and choose **Add to Home Screen**. It works offline. Records are stored only on the
device; use Trainee → Export backup to make a backup (it also imports into the Claude-hosted version).

## Structure
- `index.html`: the whole app (SEGs, checklist and the FTEP-1 form template are embedded)
- `js/standalone.js`: on-device file saving, dictation and optional Claude API calls
- `js/vendor/`: JSZip (Word form filling) and jsPDF (PDF export), kept local for offline use
- `sw.js` (offline), `manifest.webmanifest`, `icons/`
