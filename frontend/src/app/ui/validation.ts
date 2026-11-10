import { HttpErrorResponse } from '@angular/common/http';
import { FormGroup } from '@angular/forms';

export function applyServerFieldErrors(form: FormGroup, error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 400 || !Array.isArray(error.error?.errors)) return false;
  let applied = false;
  let unmapped = false;
  for (const entry of error.error.errors as unknown[]) {
    if (typeof entry !== 'string') { unmapped = true; continue; }
    const field = entry.split(':', 1)[0].trim();
    const control = form.get(field);
    if (!control) { unmapped = true; continue; }
    control.setErrors({ ...control.errors, server: true }); control.markAsTouched(); applied = true;
  }
  return applied && !unmapped;
}
