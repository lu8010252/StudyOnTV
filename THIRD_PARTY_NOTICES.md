# Third-party notices

The project's personal-use license applies to the project's original code. Bundled third-party libraries retain their own licenses.

- KaTeX 0.16.22: MIT, by the KaTeX contributors. Bundled JavaScript, stylesheets and fonts are in `shared/katex`; the license is included in `shared/katex/LICENSE` and copied into the APK with the library.
- React, Radix UI, React Hook Form, Zod, TanStack Query, Recharts, Tailwind CSS, Lucide and Sonner retain their upstream licenses. The parent panel includes compiled distributions and license files in `admin/assets/notices/`.
- shadcn-admin UI components: MIT, Copyright (c) 2024 Sat Naing. Its license is included in `admin/assets/notices/shadcn-admin.txt`.
- Mammoth (DOCX text extraction): BSD-2-Clause, retain upstream notice in `admin/assets/notices/mammoth.txt`.
- PDF.js / pdfjs-dist (PDF text extraction): Apache-2.0, retain upstream license in `admin/assets/notices/pdfjs-dist.txt`. The worker is bundled locally and loaded only when importing PDF files.
- The existing bundled QR-code generator retains the copyright and license notice in its distribution.

The reference APK inspected during interface design is not bundled with this project. No code, images or fonts from it are distributed here.
