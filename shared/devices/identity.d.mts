interface NamedDevice {id: string; name: string; room?: string; household?: string; model?: string; metadataStatus?: string;}
export function deviceTitle(device?: NamedDevice): string;
export function deviceLabel(device?: NamedDevice): string;
export function deviceContext(device: NamedDevice): string;
export function deviceAmbiguous(device: NamedDevice | undefined, devices: NamedDevice[]): boolean;
