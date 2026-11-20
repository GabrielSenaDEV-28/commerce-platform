import { ValidatorFn } from '@angular/forms';

export function isValidCpf(value: string): boolean {
  const input = value.trim();
  if (!/^(?:[0-9]{11}|[0-9]{3}\.[0-9]{3}\.[0-9]{3}-[0-9]{2})$/.test(input)) return false;
  const digits = input.replace(/[.-]/g, '');
  if (/^([0-9])\1{10}$/.test(digits)) return false;
  const checkDigit = (length: number) => {
    let sum = 0;
    for (let i = 0; i < length; i++) sum += Number(digits[i]) * (length + 1 - i);
    return (sum * 10 % 11) % 10;
  };
  return Number(digits[9]) === checkDigit(9) && Number(digits[10]) === checkDigit(10);
}

export const cpfValidator: ValidatorFn = ({ value }) =>
  typeof value === 'string' && isValidCpf(value) ? null : { invalidCpf: true };
