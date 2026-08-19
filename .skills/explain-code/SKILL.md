---
name: explain-code
description: Explain a selected piece of code in plain language, covering what it does, why it might be written that way, and any non-obvious edge cases.
---

When the user asks you to explain code, follow these steps:

1. Read the file or selection the user points to.
2. Summarise what the code does in one or two sentences.
3. Walk through the logic step by step, calling out:
   - The main control flow.
   - Any side effects (I/O, state mutation, network calls).
   - Edge cases or error handling that might surprise a reader.
4. If the code relies on a framework or library convention, name the convention and link it to the behaviour.
5. Keep the explanation short enough to read in under a minute.
