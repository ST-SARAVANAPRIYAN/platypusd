import { DetailedHTMLProps, HTMLAttributes } from 'react';

declare global {
  namespace JSX {
    interface IntrinsicElements {
      'md-filled-button': DetailedHTMLProps<HTMLAttributes<HTMLElement> & {
        disabled?: boolean;
        href?: string;
        target?: string;
      }, HTMLElement>;
      'md-outlined-button': DetailedHTMLProps<HTMLAttributes<HTMLElement> & {
        disabled?: boolean;
        href?: string;
        target?: string;
      }, HTMLElement>;
      'md-text-button': DetailedHTMLProps<HTMLAttributes<HTMLElement> & {
        disabled?: boolean;
        href?: string;
        target?: string;
      }, HTMLElement>;
      'md-switch': DetailedHTMLProps<HTMLAttributes<HTMLElement> & {
        checked?: boolean;
        disabled?: boolean;
        icons?: boolean;
        showOnlySelectedIcon?: boolean;
      }, HTMLElement>;
      'md-checkbox': DetailedHTMLProps<HTMLAttributes<HTMLElement> & {
        checked?: boolean;
        disabled?: boolean;
        indeterminate?: boolean;
      }, HTMLElement>;
      'md-radio': DetailedHTMLProps<HTMLAttributes<HTMLElement> & {
        checked?: boolean;
        disabled?: boolean;
        name?: string;
        value?: string;
      }, HTMLElement>;
    }
  }
}
